package com.killer560.hub.crashreport.mixin;

import com.killer560.hub.crashreport.CrashReportFeature;
import net.minecraft.CrashReport;
import net.minecraft.ReportType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.util.List;

/**
 * Auto Crash Reports' live path: once Minecraft has written a CRASH report (not a profile, test, network-error or
 * chunk-IO report), the feature sends it before the game exits. Both {@code saveToFile} overloads end in this one.
 */
@Mixin(CrashReport.class)
public abstract class CrashReportSaveMixin {

    @Inject(method = "saveToFile(Ljava/nio/file/Path;Lnet/minecraft/ReportType;Ljava/util/List;)Z", at = @At("RETURN"))
    private void killer560smod$sendCrash(Path file, ReportType type, List<String> extraComments, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && type == ReportType.CRASH) {
            CrashReportFeature.onCrashSaved(file);
        }
    }
}
