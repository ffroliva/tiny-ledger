package com.ffroliva.tinyledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisClient;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The rate-limit Redis client has two timeouts with different jobs, and they used to be one.
 *
 * <p>The 250ms command bound is deliberate (see {@link RateLimitConfig#rateLimitRedisClient}): it sits in
 * every request's path. But it was set as the {@code RedisURI} timeout, which Lettuce ALSO applies to the
 * connection handshake — so a Redis that takes more than 250ms to answer {@code HELLO} at startup killed the
 * application on boot. Seen in CI run 41504195375 (zap job, 2026-11-02): "Connection initialization timed
 * out after 250 millisecond(s)" on a loaded 2-vCPU runner, then BeanCreationException.
 *
 * <p>No container: a plain socket plays a Redis that is slow to complete the handshake, then answers every
 * command with an error (Lettuce falls back from RESP3 to RESP2 and connects).
 */
class RateLimitRedisClientTest {

    @Test
    void aHandshakeSlowerThanTheCommandBoundStillConnects() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread fake = Thread.ofVirtual().start(() -> serveSlowly(server, 600));
            RedisClient client = new RateLimitConfig().rateLimitRedisClient("127.0.0.1", server.getLocalPort());
            try (StatefulRedisConnection<String, byte[]> connection =
                    client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE))) {
                assertThat(connection.isOpen()).isTrue();
            } finally {
                client.shutdown();
                fake.interrupt();
            }
        }
    }

    @Test
    void commandsAreStillBoundedAt250ms() {
        RedisClient client = new RateLimitConfig().rateLimitRedisClient("127.0.0.1", 6379);
        try {
            TimeoutOptions timeouts = client.getOptions().getTimeoutOptions();
            assertThat(timeouts.isTimeoutCommands()).isTrue();
            assertThat(timeouts.getSource().getTimeout(null))
                    .isEqualTo(Duration.ofMillis(250).toNanos());
        } finally {
            client.shutdown();
        }
    }

    private static void serveSlowly(ServerSocket server, long handshakeDelayMs) {
        try (Socket socket = server.accept()) {
            BufferedReader in =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();
            Thread.sleep(handshakeDelayMs);
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("*")) { // a RESP command array: "$<len>" then the command name
                    in.readLine();
                    String name = in.readLine().toUpperCase(java.util.Locale.ROOT);
                    String reply = switch (name) {
                        case "HELLO" -> "-ERR unknown command 'HELLO'\r\n"; // forces RESP2 fallback
                        case "PING" -> "+PONG\r\n";
                        default -> "+OK\r\n";
                    };
                    out.write(reply.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                }
            }
        } catch (Exception ignored) {
            // the client closed the socket or the test interrupted the server: either way, done
        }
    }
}
