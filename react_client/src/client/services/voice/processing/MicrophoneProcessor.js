import GainController from "mediastream-gain";
import {AudioCableMiddleware} from "./AudioCableMiddleware";
import {getGlobalState, setGlobalState, store} from "../../../../state/store";
import {WorldModule} from "../../world/WorldModule";
import {VoiceModule} from "../VoiceModule";
import Hark from "hark";
import {RtcPacket} from "../peers/protocol";

export class MicrophoneProcessor {

    constructor(stream) {
        this.stream = stream;
        this.startedTalking = null;
        this.shortTriggers = 0;
        this.isStreaming = false;
        this.isMuted = false;

        this.harkEvents = Hark(this.stream, {})
        this.gainController = new GainController(stream);
        this.gainController.on();

        this.loadDefaults();
        this.monitoringVolume = 100;
        this.longSessions = 0;

        this.inputStreamSource = stream;

        let lastMonitoringState = false;
        let lastAutoAdjustmentsState = false;
        let lastStateMuted = false;
        this.enableMonitoringCheckbox = () => {};

        store.subscribe(() => {
            let {settings} = store.getState();
            if (settings.voicechatMonitoringEnabled !== lastMonitoringState) {
                lastMonitoringState = settings.voicechatMonitoringEnabled;
                this.enableMonitoringCheckbox(lastMonitoringState);
            }

            if (settings.automaticSensitivity !== lastAutoAdjustmentsState) {
                lastAutoAdjustmentsState = settings.automaticSensitivity;
                this.updateSensitivity(lastAutoAdjustmentsState);
            }

            if (settings.voicechatMuted !== lastStateMuted) {
                lastStateMuted = settings.voicechatMuted;
                if (lastStateMuted) {
                    this.onMute();
                } else {
                    this.onUnmute();
                }
            }
        })

        this.setupTrackProcessing(stream)

        // automatically check through a task how long the current speech is
        this.checkLoop = setInterval(() => {
            if (!this.isSpeaking) return;
            let timeActive = new Date().getTime() - this.startedTalking;
            let secondsTalked = (timeActive / 1000);

            if (secondsTalked > 10) {
                this.longSessions++;
                this.startedTalking = new Date().getTime();
            }

            if (this.longSessions > 1) {
                this.decreaseSensitivity()
                this.longSessions = 0;
                this.startedTalking = new Date().getTime();
            }

        }, 500);

        this.hookListeners();
    }

    updateSensitivity(toPositive) {
        let target = -Math.abs(toPositive)
        this.harkEvents.setThreshold(target)
        this.currentThreshold = this.harkEvents.threshold;
    }

    decreaseSensitivity() {
        if (!getGlobalState().settings.automaticSensitivity) return;
        let current = Math.abs(this.currentThreshold);
        current -= 5;
        this.updateSensitivity(current)
        document.getElementById("mic-sensitive-slider").value = current;
    }

    onMute() {
        this.isMuted = true;
        if (this.isSpeaking) {
            this.shouldStream(false);
        }
    }

    onUnmute() {
        this.isMuted = false;
        if (this.isSpeaking) {
            this.shouldStream(true);
        }
    }

    onSpeakStart() {
        if (this.isMuted) return;
        this.shouldStream(true);
    }

    onSpeakEnd() {
        if (this.isMuted) return;
        this.shouldStream(false);
    }

    stop() {
        this.harkEvents.stop()
        clearInterval(this.checkLoop)
    }

    shouldStream(state) {
        if (state) {
            // create start rtc notification
            if (!this.isStreaming) {
                this.isStreaming = true;
                if (VoiceModule.isReady()) {
                    VoiceModule.peerManager.sendMetaData(
                        new RtcPacket()
                            .setEventName("DISTRIBUTE_RTP")
                            .serialize()
                    )
                }
            }

            setGlobalState({voiceState: {isSpeaking: true}})

            clearTimeout(this.haltRtpTask);
            // this.gainController.on();
        } else {
            this.haltRtpTask = setTimeout(() => {
                if (VoiceModule.isReady()) {
                    this.isStreaming = false;
                    VoiceModule.peerManager.sendMetaData(
                        new RtcPacket()
                            .setEventName("HALT_RTP")
                            .serialize()
                    )
                }
            }, 500);

            setGlobalState({voiceState: {isSpeaking: false}})
            // this.gainController.off();
        }
    }

    loadDefaults() {
        let presetVolume = getGlobalState().settings.microphoneSensitivity;
        if (presetVolume != null) {
            presetVolume = parseInt(presetVolume)
            this.harkEvents.setThreshold(presetVolume)
        }
        this.currentThreshold = this.harkEvents.threshold;
        this.isSpeaking = false;
        this.harkEvents.setInterval(5)
    }

    hookListeners() {
        this.harkEvents.on('speaking', () => {
            this.isSpeaking = true;
            this.startedTalking = new Date().getTime();
            this.setMonitoringVolume(this.monitoringVolume)

            // set talking UI
            this.onSpeakStart()
        });

        this.harkEvents.on('stopped_speaking', () => {
            this.isSpeaking = false;

            // set talking UI
            this.onSpeakEnd()
            this.monitoringGainnode.gain.value = 0;

            // how long did I talk for?
            let timeActive = new Date().getTime() - this.startedTalking;
            let secondsTalked = (timeActive / 1000);
            if (secondsTalked < 1.5) {
                this.shortTriggers++;
                if (this.shortTriggers > 25) {
                    this.decreaseSensitivity();
                    this.shortTriggers = 0;
                }
            } else {
                this.shortTriggers = 0;
            }
        });
    }

    setMonitoringVolume(vol) {
        this.monitoringVolume = vol;
        this.monitoringGainnode.gain.value = (vol / 100);
    }

    setupTrackProcessing(stream) {
        const ctx = WorldModule.player.audioCtx;
        this.monitoringAudio = new Audio();
        this.monitoringAudio.muted = true;
        this.monitoringAudio.autoplay = true
        this.monitoringAudio.volume = 1
        this.output = ctx.createMediaStreamDestination()

        this.monitoringAudio.srcObject = this.output.stream;
        this.monitoringGainnode = ctx.createGain();

        this.enableMonitoringCheckbox((allow) => {
                if (allow) {
                    this.monitoringAudio.muted = false;
                } else {
                    this.monitoringAudio.muted = true;
                }
            })

        let src = ctx.createMediaStreamSource(this.inputStreamSource)

        let shiftMiddleware = new AudioCableMiddleware()
        shiftMiddleware.link(ctx, src, this.output)
        this.monitoringAudio.play()

    }

}
