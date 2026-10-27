package com.gatar.recipedealer.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gatar.recipedealer.auth.AccountService.Session;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** Registration and login need no token ({@code /v1/auth/**} is open, see {@code SecurityConfig}). */
@RestController
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    public record RegisterRequest(@NotBlank @Size(max = 100) String userName, @NotEmpty String password,
                                  @NotBlank @Size(max = 100) String deviceName, String registrationCode) {
    }

    public record LoginRequest(@NotBlank @Size(max = 100) String userName, @NotEmpty String password,
                               @NotBlank @Size(max = 100) String deviceName) {
    }

    public record PasswordRequest(@NotEmpty String password, String currentPassword) {
    }

    @PostMapping("/v1/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Session register(@Valid @RequestBody RegisterRequest request) {
        return accounts.register(request.userName(), request.password(), request.deviceName(), request.registrationCode());
    }

    @PostMapping("/v1/auth/login")
    @ResponseStatus(HttpStatus.CREATED)
    public Session login(@Valid @RequestBody LoginRequest request) {
        return accounts.login(request.userName(), request.password(), request.deviceName());
    }

    @PutMapping("/v1/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setPassword(@Valid @RequestBody PasswordRequest request, @AuthenticationPrincipal DevicePrincipal caller) {
        accounts.setPassword(caller, request.password(), request.currentPassword());
    }
}
