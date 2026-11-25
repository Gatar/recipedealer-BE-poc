package com.gatar.recipedealer.household;

import java.util.List;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gatar.recipedealer.auth.AccountService.Session;
import com.gatar.recipedealer.auth.DevicePrincipal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Households: creating one and joining one need no token ({@code /v1/auth/**}); everything else acts on the
 * caller's household.
 */
@RestController
@EnableConfigurationProperties(HouseholdProperties.class)
public class HouseholdController {

    private final HouseholdService households;

    public HouseholdController(HouseholdService households) {
        this.households = households;
    }

    public record CreateRequest(@NotBlank @Size(max = 100) String householdName,
                                @NotBlank @Size(max = 100) String userName, @NotEmpty String password,
                                @NotBlank @Size(max = 100) String deviceName) {
    }

    public record JoinRequest(@NotBlank String invitationCode, @NotBlank @Size(max = 100) String userName,
                              @NotEmpty String password, @NotBlank @Size(max = 100) String deviceName) {
    }

    public record RenameRequest(@NotBlank @Size(max = 100) String name) {
    }

    public record RoleRequest(@NotBlank String role) {
    }

    @PostMapping("/v1/auth/households")
    @ResponseStatus(HttpStatus.CREATED)
    public Session create(@Valid @RequestBody CreateRequest request) {
        return households.createHousehold(request.householdName(), request.userName(), request.password(),
                request.deviceName());
    }

    @PostMapping("/v1/auth/join")
    @ResponseStatus(HttpStatus.CREATED)
    public Session join(@Valid @RequestBody JoinRequest request) {
        return households.join(request.invitationCode(), request.userName(), request.password(), request.deviceName());
    }

    @GetMapping("/v1/me")
    public HouseholdService.MeView me(@AuthenticationPrincipal DevicePrincipal caller) {
        return households.me(caller);
    }

    @DeleteMapping("/v1/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMe(@AuthenticationPrincipal DevicePrincipal caller) {
        households.deleteMe(caller);
    }

    @PatchMapping("/v1/household")
    public HouseholdService.HouseholdView rename(@Valid @RequestBody RenameRequest request,
                                                 @AuthenticationPrincipal DevicePrincipal caller) {
        return households.rename(caller, request.name());
    }

    @DeleteMapping("/v1/household")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal DevicePrincipal caller) {
        households.deleteHousehold(caller);
    }

    @GetMapping("/v1/household/members")
    public List<HouseholdService.MemberView> members(@AuthenticationPrincipal DevicePrincipal caller) {
        return households.members(caller);
    }

    @PatchMapping("/v1/household/members/{userId}")
    public List<HouseholdService.MemberView> setRole(@PathVariable UUID userId, @Valid @RequestBody RoleRequest request,
                                                     @AuthenticationPrincipal DevicePrincipal caller) {
        return households.setRole(caller, userId, request.role());
    }

    @DeleteMapping("/v1/household/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable UUID userId, @AuthenticationPrincipal DevicePrincipal caller) {
        households.removeMember(caller, userId);
    }

    @DeleteMapping("/v1/household/devices/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeDevice(@PathVariable UUID deviceId, @AuthenticationPrincipal DevicePrincipal caller) {
        households.revokeDevice(caller, deviceId);
    }

    @PostMapping("/v1/household/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public HouseholdService.InvitationView invite(@AuthenticationPrincipal DevicePrincipal caller) {
        return households.invite(caller);
    }

    @GetMapping("/v1/household/invitations")
    public List<HouseholdService.InvitationView> invitations(@AuthenticationPrincipal DevicePrincipal caller) {
        return households.invitations(caller);
    }

    @DeleteMapping("/v1/household/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInvitation(@PathVariable UUID invitationId, @AuthenticationPrincipal DevicePrincipal caller) {
        households.revokeInvitation(caller, invitationId);
    }
}
