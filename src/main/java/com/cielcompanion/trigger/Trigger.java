package com.cielcompanion.trigger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A simple client to send a trigger signal to the main Ciel Companion application.
 * It works by establishing a TCP connection and sending a secret passphrase.
 * REWORKED: Now accepts port and passphrase as command-line arguments.
 */
public class Trigger {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java com.cielcompanion.trigger.Trigger <port> <passphrase>");
            return;
        }

        String serverIp = "127.0.0.1";
        int port = Integer.parseInt(args[0]);
        String passphrase = args[1];

        System.out.println("Attempting to send trigger signal to Ciel Companion on port " + port + "...");
        try (Socket socket = new Socket(serverIp, port)) {
            try (OutputStream os = socket.getOutputStream()) {
                os.write((passphrase + "\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
                System.out.println("Successfully connected. Trigger signal sent.");
            }
        } catch (IOException e) {
            System.err.println("Failed to send trigger signal. Is the main Ciel Companion application running?");
            System.err.println("Error details: " + e.getMessage());
        }
    }
}
