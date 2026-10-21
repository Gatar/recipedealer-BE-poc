package com.gatar.recipedealer.lock;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gatar.recipedealer.auth.DevicePrincipal;

/** Edit lease: acquire when the editor opens, heartbeat every 2 minutes, release on cancel. */
@RestController
@RequestMapping("/v1/recipes/{recipeId}/lock")
public class LockController {

    private final LockService locks;

    public LockController(LockService locks) {
        this.locks = locks;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LockService.Lease acquire(@PathVariable UUID recipeId, @AuthenticationPrincipal DevicePrincipal device) {
        return locks.acquire(recipeId, device);
    }

    @PutMapping("/{lockId}")
    public LockService.Lease heartbeat(@PathVariable UUID recipeId, @PathVariable UUID lockId) {
        return locks.heartbeat(recipeId, lockId);
    }

    @DeleteMapping("/{lockId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable UUID recipeId, @PathVariable UUID lockId) {
        locks.release(recipeId, lockId);
    }
}
