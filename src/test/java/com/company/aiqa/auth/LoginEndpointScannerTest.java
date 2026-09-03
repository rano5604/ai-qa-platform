package com.company.aiqa.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Discovery reads the target's own source so a run supplies only credentials.
 * The fixtures mirror a typical Spring auth controller + token DTO + security
 * config, the shape TailorBookApp and most Spring services take.
 */
class LoginEndpointScannerTest {

    private void write(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), content);
    }

    @Test
    void discoversLoginPathTokenFieldAndMechanism(@TempDir Path repo) throws IOException {
        Path src = repo.resolve("src/main/java/com/app");
        write(src, "AuthController.java", """
                package com.app;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/api/auth")
                public class AuthController {
                    @PostMapping("/signin")
                    public JwtResponse signin(@RequestBody LoginRequest req) {
                        return new JwtResponse();
                    }
                }
                """);
        write(src, "JwtResponse.java", """
                package com.app;
                public class JwtResponse {
                    private String token;
                    private String type = "Bearer";
                    public String getToken() { return token; }
                }
                """);
        write(src, "SecurityConfig.java", """
                package com.app;
                import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
                @EnableWebSecurity
                public class SecurityConfig {
                    // SecurityFilterChain with a JWT OncePerRequestFilter and Bearer tokens
                    // OncePerRequestFilter Bearer
                }
                """);

        LoginEndpointScanner.LoginDiscovery d = LoginEndpointScanner.discover(repo);

        assertEquals("/api/auth/signin", d.path());
        assertEquals("token", d.tokenPath());
        assertTrue(d.mechanism().contains("Spring Security"), d.mechanism());
    }

    @Test
    void discoversAccessTokenFieldName(@TempDir Path repo) throws IOException {
        Path src = repo.resolve("src/main/java/com/app");
        write(src, "LoginController.java", """
                package com.app;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class LoginController {
                    @PostMapping("/login")
                    public TokenDto login(@RequestBody Object req) { return new TokenDto(); }
                }
                """);
        write(src, "TokenDto.java", """
                package com.app;
                public record TokenDto(String accessToken, long expiresIn) {}
                """);

        LoginEndpointScanner.LoginDiscovery d = LoginEndpointScanner.discover(repo);

        assertEquals("/login", d.path());
        assertEquals("accessToken", d.tokenPath());
    }

    @Test
    void emptyDiscoveryWhenThereIsNoAuthAnywhere(@TempDir Path repo) throws IOException {
        Path src = repo.resolve("src/main/java/com/app");
        write(src, "OrderController.java", """
                package com.app;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/api/orders")
                public class OrderController {
                    @PostMapping
                    public String create(@RequestBody String body) { return "ok"; }
                }
                """);

        LoginEndpointScanner.LoginDiscovery d = LoginEndpointScanner.discover(repo);

        assertNull(d.path());
        assertNull(d.mechanism());
    }

    @Test
    void nullRepoIsEmptyNotAnError() {
        assertTrue(LoginEndpointScanner.discover(null).isEmpty());
    }
}
