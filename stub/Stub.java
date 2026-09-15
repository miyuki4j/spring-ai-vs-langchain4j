import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Minimal stub that pretends to be the DeepSeek chat-completions endpoint.
 * It records every request body to stub/req-N.json and answers with a valid
 * OpenAI-compatible ChatCompletion so Spring AI can parse it.
 *
 * Run:  java Stub.java
 */
public class Stub {

    private static int counter = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get("stub");
        Files.createDirectories(dir);

        HttpServer server = HttpServer.create(new InetSocketAddress(9099), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            int n;
            synchronized (Stub.class) {
                n = ++counter;
            }
            Path out = dir.resolve("req-" + n + ".json");
            Files.writeString(out, body, StandardCharsets.UTF_8);
            System.out.println("captured -> " + out.toAbsolutePath());

            String json = "{\"id\":\"stub-1\",\"object\":\"chat.completion\",\"created\":1700000000,"
                    + "\"model\":\"deepseek-flash\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"STUB-OK\"},"
                    + "\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";

            byte[] resp = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        System.out.println("stub listening on http://localhost:9099/");
    }
}
