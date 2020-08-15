package com.craftmend.openaudiomc.generic.networking.io;

import com.craftmend.openaudiomc.OpenAudioMc;
import com.craftmend.openaudiomc.generic.core.logging.OpenAudioLogger;
import com.craftmend.openaudiomc.generic.networking.certificate.CertificateHelper;
import com.craftmend.openaudiomc.generic.networking.client.objects.player.ClientConnection;
import com.craftmend.openaudiomc.generic.networking.abstracts.AbstractPacket;
import com.craftmend.openaudiomc.generic.networking.drivers.ClientDriver;
import com.craftmend.openaudiomc.generic.networking.drivers.SystemDriver;
import com.craftmend.openaudiomc.generic.networking.interfaces.Authenticatable;
import com.craftmend.openaudiomc.generic.networking.interfaces.SocketDriver;
import com.craftmend.openaudiomc.generic.networking.payloads.AcknowledgeClientPayload;
import com.craftmend.openaudiomc.generic.networking.rest.RestRequest;
import com.craftmend.openaudiomc.generic.networking.rest.endpoints.RestEndpoint;
import com.craftmend.openaudiomc.generic.networking.rest.interfaces.ApiResponse;
import com.craftmend.openaudiomc.generic.networking.rest.responses.LoginResponse;
import com.craftmend.openaudiomc.generic.platform.Platform;
import com.craftmend.openaudiomc.generic.state.states.AssigningRelayState;
import com.craftmend.openaudiomc.generic.state.states.ConnectedState;
import com.craftmend.openaudiomc.generic.state.states.ConnectingState;
import com.craftmend.openaudiomc.generic.state.states.IdleState;
import com.craftmend.openaudiomc.generic.core.storage.enums.StorageKey;

import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Socket;

import lombok.RequiredArgsConstructor;

import okhttp3.OkHttpClient;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@RequiredArgsConstructor
public class SocketIoConnector {

    private Socket socket;
    private RestRequest plusHandler;
    private RestRequest logoutHandler;
    private boolean registeredLogout = false;

    private final SocketDriver[] drivers = new SocketDriver[]{
            new ClientDriver(),
            new SystemDriver(),
    };

    public void setupConnection() {
        if (!OpenAudioMc.getInstance().getStateService().getCurrentState().canConnect()) return;
        // update state
        OpenAudioMc.getInstance().getStateService().setState(new AssigningRelayState());

        if (!registeredLogout) {
            plusHandler = new RestRequest(RestEndpoint.PLUS_LOGIN);
            logoutHandler = new RestRequest(RestEndpoint.PLUS_LOGOUT);
            OpenAudioMc.getInstance().getStateService().addListener((oldState, updatedState) -> {
                if (oldState instanceof ConnectedState) {
                    logoutHandler.executeAsync();
                }
            });
            registeredLogout = true;
        }

        ProxySelector.setDefault(new NullProxySelector());

        OkHttpClient okHttpClient = CertificateHelper.ignore(new OkHttpClient.Builder().proxySelector(new NullProxySelector())).build();

        IO.Options opts = new IO.Options();
        opts.callFactory = okHttpClient;
        opts.reconnection = false;
        opts.webSocketFactory = okHttpClient;

        // load keys
        String privateKey = OpenAudioMc.getInstance().getAuthenticationService().getServerKeySet().getPrivateKey().getValue();
        String publicKey = OpenAudioMc.getInstance().getAuthenticationService().getServerKeySet().getPublicKey().getValue();

        // authentication headers
        opts.query = "type=server&" +
                "private=" + privateKey +
                "&public=" + publicKey;

        // request a relay server
        OpenAudioLogger.toConsole("Requesting relay..");

        // schedule timeout check
        OpenAudioMc.getInstance().getTaskProvider().schduleSyncDelayedTask(() -> {
            if (OpenAudioMc.getInstance().getStateService().getCurrentState() instanceof AssigningRelayState) {
                OpenAudioLogger.toConsole("Connecting timed out.");
                OpenAudioMc.getInstance().getStateService().setState(new IdleState("Connecting to the relay timed out"));
            }
        }, 20 * 35);

        Instant request = Instant.now();

        ApiResponse response = plusHandler.executeInThread();

        if (!response.getErrors().isEmpty()) {
            OpenAudioMc.getInstance().getStateService().setState(new IdleState("Failed to do the initial handshake. Error: " + response.getErrors().get(0).getCode()));
            OpenAudioLogger.toConsole("Failed to get relay host.");
            OpenAudioLogger.toConsole(" - message: " + response.getErrors().get(0).getMessage());
            OpenAudioLogger.toConsole(" - code: " + response.getErrors().get(0).getCode());
            try {
                throw new IOException("Failed to get relay! see console for error information");
            } catch (IOException e) {
                e.printStackTrace(); 
            }
            return;
        }

        LoginResponse loginResponse = response.getResponse(LoginResponse.class);
        Instant finish = Instant.now();
        OpenAudioLogger.toConsole("Assigned relay: " + loginResponse.getAssignedOpenAudioServer().getSecureEndpoint() + " request took " + Duration.between(request, finish).toMillis() + "MS");

        // setup socketio connection
        try {
            socket = IO.socket(loginResponse.getAssignedOpenAudioServer().getInsecureEndpoint(), opts);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }

        // register state to be connecting
        OpenAudioMc.getInstance().getStateService().setState(new ConnectingState());

        // schedule timeout check
        OpenAudioMc.getInstance().getTaskProvider().schduleSyncDelayedTask(() -> {
            if (OpenAudioMc.getInstance().getStateService().getCurrentState() instanceof ConnectingState) {
                OpenAudioLogger.toConsole("Connecting timed out.");
                OpenAudioMc.getInstance().getStateService().setState(new IdleState("Connecting to the relay timed out (socket)"));
            }
        }, 20 * 35);


        // register drivers
        for (SocketDriver driver : drivers) {
            driver.boot(socket);
        }
        socket.connect();
    }

    public void disconnect() {
        this.socket.disconnect();
    }

    public void send(Authenticatable client, AbstractPacket packet) {
        // only send the packet if the client is online, valid and the plugin is connected
        if (client.getIsConnected() && OpenAudioMc.getInstance().getStateService().getCurrentState().isConnected()) {
            packet.setClient(client.getOwnerUUID());
            socket.emit("data", OpenAudioMc.getGson().toJson(packet));
        }
    }
}
