package com.gatar.recipedealer.admin;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
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
import com.gatar.recipedealer.web.NotFoundException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/v1/admin")
public class AdminController {

    private final DeviceTokenService tokens;

    public AdminController(DeviceTokenService tokens) {
        this.tokens = tokens;
    }

    public record EnrollmentRequest(@NotBlank @Size(max = 100) String userName,
                                    @NotBlank @Size(max = 100) String deviceName) {
    }

    @PostMapping("/enrollments")
    @ResponseStatus(HttpStatus.CREATED)
    public Enrollment enroll(@Valid @RequestBody EnrollmentRequest request) {
        return tokens.enroll(request.userName(), request.deviceName());
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
}
