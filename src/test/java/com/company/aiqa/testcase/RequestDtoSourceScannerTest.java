package com.company.aiqa.testcase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mms scenario this exists for: {@code CreateMerchantRequest.accountNo} is
 * {@code @NotBlank} on the DTO but never made it into the service's own
 * OpenAPI {@code required} array, so the contract-only repair had no way to
 * know. This class reads the DTO directly, as a second, independent source of
 * truth.
 */
class RequestDtoSourceScannerTest {

    @Test
    void findsFieldsWithPresenceAnnotationsRegardlessOfValidationPackage(@TempDir Path repo) throws IOException {
        writeClass(repo, "CreateMerchantRequest", """
                package com.example.mms.dto;

                import jakarta.validation.constraints.NotBlank;
                import javax.validation.constraints.NotNull;

                public class CreateMerchantRequest {
                    @NotNull
                    private Long userId;

                    @NotBlank
                    private String accountNo;

                    private String website;
                }
                """);

        Set<String> required = RequestDtoSourceScanner.requiredFieldNames(repo, "CreateMerchantRequest");

        assertEquals(Set.of("userId", "accountNo"), required);
    }

    @Test
    void anUnannotatedFieldIsNotReported(@TempDir Path repo) throws IOException {
        writeClass(repo, "Widget", """
                package com.example;

                public class Widget {
                    private String name;
                }
                """);

        assertTrue(RequestDtoSourceScanner.requiredFieldNames(repo, "Widget").isEmpty());
    }

    @Test
    void findsARecordDtoAndItsComponents(@TempDir Path repo) throws IOException {
        writeClass(repo, "CreateOrderRequest", """
                package com.example;

                import jakarta.validation.constraints.NotEmpty;

                public record CreateOrderRequest(@NotEmpty String sku, int quantity) {
                }
                """);

        assertEquals(Set.of("sku"), RequestDtoSourceScanner.requiredFieldNames(repo, "CreateOrderRequest"));
    }

    @Test
    void skipsBuildAndVcsDirectoriesRatherThanFindingAStaleCopy(@TempDir Path repo) throws IOException {
        writeClass(repo, "target/classes/generated/Merchant", "package x; class Merchant { private String stale; }");
        writeClass(repo, "src/main/java/com/example/Merchant", """
                package com.example;

                import jakarta.validation.constraints.NotBlank;

                public class Merchant {
                    @NotBlank
                    private String legalName;
                }
                """);

        assertEquals(Set.of("legalName"), RequestDtoSourceScanner.requiredFieldNames(repo, "Merchant"));
    }

    @Test
    void aMissingClassReturnsEmptyRatherThanThrowing(@TempDir Path repo) {
        assertTrue(RequestDtoSourceScanner.requiredFieldNames(repo, "NoSuchClass").isEmpty());
    }

    @Test
    void nullRepoRootIsANoOp() {
        assertTrue(RequestDtoSourceScanner.requiredFieldNames(null, "CreateMerchantRequest").isEmpty());
    }

    @Test
    void blankClassNameIsANoOp(@TempDir Path repo) {
        assertTrue(RequestDtoSourceScanner.requiredFieldNames(repo, "").isEmpty());
    }

    private static void writeClass(Path repo, String relativePathNoExtension, String source) throws IOException {
        Path file = repo.resolve(relativePathNoExtension + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }
}
