package com.gatar.recipedealer.admin;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gatar.recipedealer.auth.DeviceTokenService;
import com.gatar.recipedealer.auth.DeviceTokenService.Device;
import com.gatar.recipedealer.auth.DeviceTokenService.Enrollment;
import com.gatar.recipedealer.household.Households;
import com.gatar.recipedealer.web.NotFoundException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/v1/admin")
public class AdminController {

    private final DeviceTokenService tokens;
    private final JdbcClient jdbc;

    public AdminController(DeviceTokenService tokens, JdbcClient jdbc) {
        this.tokens = tokens;
        this.jdbc = jdbc;
    }

    /** {@code householdId}: where a new user goes; the default household when missing. */
    public record EnrollmentRequest(@NotBlank @Size(max = 100) String userName,
                                    @NotBlank @Size(max = 100) String deviceName, UUID householdId) {
    }

    @PostMapping("/enrollments")
    @ResponseStatus(HttpStatus.CREATED)
    public Enrollment enroll(@Valid @RequestBody EnrollmentRequest request) {
        UUID householdId = request.householdId() == null ? Households.DEFAULT_ID : request.householdId();
        boolean live = jdbc.sql("SELECT EXISTS (SELECT 1 FROM household WHERE id = :id AND deleted_at IS NULL)")
                .param("id", householdId).query(Boolean.class).single();
        if (!live) {
            throw new NotFoundException("household " + householdId + " not found");
        }
        return tokens.enroll(householdId, request.userName(), request.deviceName());
    }

    @GetMapping("/devices")
    public List<Device> devices() {
        return tokens.devices();
    }

    @DeleteMapping("/devices/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID deviceId) {
        if (!tokens.revoke(deviceId)) {
            throw new NotFoundException("device " + deviceId + " not found");
        }
    }

    /** Soft delete: the user's history stays, its devices stop working, its name can be registered again. */
    @DeleteMapping("/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable UUID userId) {
        if (!tokens.deleteUser(userId)) {
            throw new NotFoundException("user " + userId + " not found");
        }
    }
}
