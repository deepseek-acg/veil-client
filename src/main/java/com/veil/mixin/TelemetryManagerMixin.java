package com.veil.mixin;

import net.minecraft.client.telemetry.ClientTelemetryManager;
import net.minecraft.client.telemetry.TelemetryEventSender;
import net.minecraft.client.telemetry.WorldSessionTelemetryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.time.Duration;
import java.util.UUID;

/**
 * Anti-fingerprint — Mojang telemetry suppression.
 *
 * <p>Vanilla sends world load, join and performance telemetry to Mojang. Sessions get their sender
 * from {@code ClientTelemetryManager}, so both the outside-session sender and every world-session
 * manager are redirected to {@link TelemetryEventSender#DISABLED} and the events are discarded
 * before they are built.
 *
 * <p>26.3 note: there is no {@code TelemetryManager} class and no single event-logger hook; the
 * manager is {@code ClientTelemetryManager} and the sender is handed out from two methods.
 */
@Mixin(ClientTelemetryManager.class)
public abstract class TelemetryManagerMixin {

    @Inject(method = "getOutsideSessionSender", at = @At("HEAD"), cancellable = true)
    private void blockOutsideSession(CallbackInfoReturnable<TelemetryEventSender> cir) {
        cir.setReturnValue(TelemetryEventSender.DISABLED);
    }

    @Inject(method = "createWorldSessionManager", at = @At("HEAD"), cancellable = true)
    private void blockWorldSession(boolean demo, Duration duration, String serverBrand, UUID sessionId,
                                   CallbackInfoReturnable<WorldSessionTelemetryManager> cir) {
        cir.setReturnValue(new WorldSessionTelemetryManager(
                TelemetryEventSender.DISABLED, demo, duration, serverBrand, sessionId));
    }
}
