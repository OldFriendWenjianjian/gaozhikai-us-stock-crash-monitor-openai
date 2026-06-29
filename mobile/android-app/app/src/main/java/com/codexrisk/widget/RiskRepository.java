package com.codexrisk.widget;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class RiskRepository {
    private static final String SNAPSHOT_HOST = "2402:4e00:c013:8600:5602:3dc2:a2d0:0";
    private static final int SNAPSHOT_PORT = 80;
    private static final String SNAPSHOT_PATH = "/us-stock-20260629/crash-monitor/latest.json";
    static final String SNAPSHOT_URL = "http://[" + SNAPSHOT_HOST + "]" + SNAPSHOT_PATH;
    private static final int MAX_REDIRECTS = 5;

    private RiskRepository() {
    }

    static RiskSnapshot fetchLatest() throws Exception {
        try {
            return fetchLatestRawHttp();
        } catch (Exception rawHttpError) {
            try {
                return fetchLatestWithUrlConnection();
            } catch (Exception urlConnectionError) {
                rawHttpError.addSuppressed(urlConnectionError);
                throw rawHttpError;
            }
        }
    }

    private static RiskSnapshot fetchLatestWithUrlConnection() throws Exception {
        URL url = new URL(SNAPSHOT_URL);
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent", "CodexRiskWidgetAndroid/1.0");
            try {
                int code = connection.getResponseCode();
                if (code >= 200 && code < 300) {
                    try (InputStream input = connection.getInputStream()) {
                        return RiskSnapshot.fromJson(readAll(input));
                    }
                }
                if (isRedirect(code)) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        throw new IllegalStateException("HTTP " + code + " without Location");
                    }
                    url = new URL(url, location);
                    continue;
                }
                throw new IllegalStateException("HTTP " + code + " Location=" + connection.getHeaderField("Location"));
            } finally {
                connection.disconnect();
            }
        }
        throw new IllegalStateException("Too many redirects");
    }

    private static RiskSnapshot fetchLatestRawHttp() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName(SNAPSHOT_HOST), SNAPSHOT_PORT), 8000);
            socket.setSoTimeout(8000);
            OutputStream output = socket.getOutputStream();
            String request = "GET " + SNAPSHOT_PATH + " HTTP/1.1\r\n"
                    + "Host: [" + SNAPSHOT_HOST + "]\r\n"
                    + "User-Agent: CodexRiskWidgetAndroid/1.0\r\n"
                    + "Accept: application/json\r\n"
                    + "Connection: close\r\n\r\n";
            output.write(request.getBytes(StandardCharsets.US_ASCII));
            output.flush();

            byte[] response = readAllBytes(socket.getInputStream());
            int bodyOffset = headerEndOffset(response);
            if (bodyOffset < 0) {
                throw new IllegalStateException("Invalid HTTP response");
            }
            String headers = new String(response, 0, bodyOffset, StandardCharsets.ISO_8859_1);
            int status = statusCode(headers);
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Raw HTTP " + status + " Location=" + headerValue(headers, "Location"));
            }
            String body = new String(response, bodyOffset + 4, response.length - bodyOffset - 4, StandardCharsets.UTF_8);
            return RiskSnapshot.fromJson(body);
        }
    }

    private static boolean isRedirect(int code) {
        return code == HttpURLConnection.HTTP_MOVED_PERM
                || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_SEE_OTHER
                || code == 307
                || code == 308;
    }

    private static byte[] readAllBytes(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static int headerEndOffset(byte[] response) {
        for (int index = 0; index <= response.length - 4; index++) {
            if (response[index] == '\r'
                    && response[index + 1] == '\n'
                    && response[index + 2] == '\r'
                    && response[index + 3] == '\n') {
                return index;
            }
        }
        return -1;
    }

    private static int statusCode(String headers) {
        int lineEnd = headers.indexOf("\r\n");
        String statusLine = lineEnd >= 0 ? headers.substring(0, lineEnd) : headers;
        String[] parts = statusLine.split(" ");
        if (parts.length < 2) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String headerValue(String headers, String name) {
        String prefix = name + ":";
        String[] lines = headers.split("\r\n");
        for (String line : lines) {
            if (line.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static String readAll(InputStream input) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }
}
