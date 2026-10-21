package com.gatar.recipedealer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.jayway.jsonpath.JsonPath;

/** Helpers for authenticated requests in integration tests. */
public final class TestAuth {

    private TestAuth() {
    }

    public static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    public static String adminBasic() {
        return basic(TestcontainersConfiguration.ADMIN_USERNAME, TestcontainersConfiguration.ADMIN_PASSWORD);
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    public static MvcTestResult enrollRequest(MockMvcTester mvc, String userName, String deviceName) {
        return mvc.post().uri("/v1/admin/enrollments")
                .header(HttpHeaders.AUTHORIZATION, adminBasic())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userName\":\"%s\",\"deviceName\":\"%s\"}".formatted(userName, deviceName))
                .exchange();
    }

    /** Enrolls a device and returns its bearer token. */
    public static String enroll(MockMvcTester mvc, String userName, String deviceName) {
        var result = enrollRequest(mvc, userName, deviceName);
        assertThat(result).hasStatus(201);
        return JsonPath.read(body(result), "$.token");
    }

    public static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
