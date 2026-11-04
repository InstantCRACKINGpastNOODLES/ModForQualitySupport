package com.example.examplemod;

import java.util.Optional;
import java.util.UUID;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Client-side 3D visual tether renderer connecting the player to the bound Maid/worker.
 * Active ONLY during explicit JOB_AUTHORING mode, active order selection (worksite and storage),
 * and confirmation feedback. Tether persists seamlessly across hotbar slot changes during authoring.
 * In idle bound state, NO tether is rendered.
 */
public final class BindingTicketTetherRenderer {

    private static final int SEGMENTS = 20;

    private BindingTicketTetherRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.options.hideGui) {
            return;
        }

        // Rule 1: Tether appears ONLY during JOB_AUTHORING, worksite/storage selection, or confirmation pulse.
        // Binding alone in idle state NEVER activates the tether.
        boolean authoringActive = BindingTicketHUD.isAuthoringActive();
        boolean confirmedAck = BindingTicketHUD.isConfirmedAck();
        if (!authoringActive && !confirmedAck) {
            return;
        }

        // Rule 2: Tether persists across hotbar slot changes.
        // Resolve target worker UUID directly from active HUD session.
        UUID boundWorkerId = BindingTicketHUD.getActiveWorkerId();
        if (boundWorkerId == null) {
            return;
        }

        // Determine hand anchor point
        ItemStack mainHand = player.getMainHandItem();
        ItemStack offHand = player.getOffhandItem();
        boolean offHeld = offHand.getItem() instanceof BindingTicketItem;
        boolean mainHeld = !offHeld; // Default to main hand arm anchor if holding chest/tools/empty

        // 2. Resolve bound worker in client level
        Entity boundEntity = findEntityByUUID(level, boundWorkerId);
        if (!(boundEntity instanceof Mob worker) || !worker.isAlive() || worker.isRemoved()) {
            return;
        }

        // Visual distance cull check between player and worker
        double maxTetherDist = Config.getMaxTetherDistance();
        double distSqr = player.distanceToSqr(worker);
        if (distSqr > maxTetherDist * maxTetherDist) {
            return;
        }

        // 3. Determine tether visual styling based on authoring sub-state
        boolean inOrderSelection = BindingTicketHUD.isInSelection();

        float r, g, b, a;
        float pulseSpeed;
        if (confirmedAck) {
            // Emerald green confirmation pulse
            r = 0.15f;
            g = 0.95f;
            b = 0.35f;
            a = 1.0f;
            pulseSpeed = 8.0f;
        } else if (inOrderSelection) {
            // Bright amber / golden orange during worksite & storage selection
            r = 1.0f;
            g = 0.72f;
            b = 0.12f;
            a = 0.95f;
            pulseSpeed = 4.0f;
        } else {
            // Azure cyan pulse during active JOB_AUTHORING mode
            r = 0.22f;
            g = 0.78f;
            b = 1.0f;
            a = 0.85f;
            pulseSpeed = 3.0f;
        }

        // 4. Calculate 3D start point (player hand/ticket) and end point (worker center)
        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        Vec3 startPos = calculateTicketPosition(mc, player, camera, mainHeld);
        Vec3 endPos = new Vec3(
                worker.getX(),
                worker.getY() + worker.getBbHeight() * 0.55,
                worker.getZ()
        );

        // 5. Render 3D beam segments
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        // Camera-relative coordinate space
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f matrix = poseStack.last().pose();

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());

        long timeMs = System.currentTimeMillis();
        float timeSec = (timeMs % 100_000L) / 1000.0f;

        Vec3 prevPoint = startPos;
        for (int i = 1; i <= SEGMENTS; i++) {
            float t = (float) i / SEGMENTS;
            // Linear interpolation with a gentle catenary sag and animated wave
            double x = Mth.lerp(t, startPos.x, endPos.x);
            double y = Mth.lerp(t, startPos.y, endPos.y);
            double z = Mth.lerp(t, startPos.z, endPos.z);

            // Catenary sag (maximum at midpoint t=0.5)
            double sag = Math.sin(t * Math.PI) * 0.25;
            // Harmonic wave pulse
            double wave = Math.sin(t * Math.PI * 3.0 - timeSec * pulseSpeed) * 0.04;
            y = y - sag + wave;

            Vec3 currPoint = new Vec3(x, y, z);

            // Compute segment normal vector
            Vec3 dir = currPoint.subtract(prevPoint).normalize();
            float nx = (float) dir.x;
            float ny = (float) dir.y;
            float nz = (float) dir.z;

            // Draw core beam line
            consumer.addVertex(matrix, (float) prevPoint.x, (float) prevPoint.y, (float) prevPoint.z)
                    .setColor(r, g, b, a)
                    .setNormal(nx, ny, nz);
            consumer.addVertex(matrix, (float) currPoint.x, (float) currPoint.y, (float) currPoint.z)
                    .setColor(r, g, b, a)
                    .setNormal(nx, ny, nz);

            // Draw slightly offset glow beam line for visual thickness
            float glowAlpha = a * 0.45f;
            float off = 0.02f;
            consumer.addVertex(matrix, (float) prevPoint.x, (float) (prevPoint.y + off), (float) prevPoint.z)
                    .setColor(r, g, b, glowAlpha)
                    .setNormal(nx, ny, nz);
            consumer.addVertex(matrix, (float) currPoint.x, (float) (currPoint.y + off), (float) currPoint.z)
                    .setColor(r, g, b, glowAlpha)
                    .setNormal(nx, ny, nz);

            prevPoint = currPoint;
        }

        // Flush lines buffer
        bufferSource.endBatch(RenderType.lines());
        poseStack.popPose();
    }

    private static Vec3 calculateTicketPosition(Minecraft mc, Player player, Camera camera, boolean mainHandHeld) {
        if (mc.options.getCameraType().isFirstPerson()) {
            Vec3 camPos = camera.getPosition();
            Vec3 forward = Vec3.directionFromRotation(camera.getXRot(), camera.getYRot());
            Vec3 up = Vec3.directionFromRotation(camera.getXRot() - 90.0f, camera.getYRot());
            Vec3 right = forward.cross(up).normalize();

            boolean rightHanded = player.getMainArm() == HumanoidArm.RIGHT;
            boolean isRightArm = mainHandHeld ? rightHanded : !rightHanded;
            double sideOffset = isRightArm ? 0.32 : -0.32;
            double downOffset = -0.22;
            double forwardOffset = 0.42;

            return camPos
                    .add(right.scale(sideOffset))
                    .add(up.scale(downOffset))
                    .add(forward.scale(forwardOffset));
        } else {
            // Third-person: compute from player position and arm
            double yawRad = Math.toRadians(player.yBodyRot);
            boolean rightHanded = player.getMainArm() == HumanoidArm.RIGHT;
            boolean isRightArm = mainHandHeld ? rightHanded : !rightHanded;
            double sideOffset = isRightArm ? -0.38 : 0.38;

            double armX = player.getX() + Math.cos(yawRad) * sideOffset;
            double armY = player.getY() + player.getBbHeight() * 0.65;
            double armZ = player.getZ() + Math.sin(yawRad) * sideOffset;

            return new Vec3(armX, armY, armZ);
        }
    }

    private static Entity findEntityByUUID(ClientLevel level, UUID uuid) {
        if (level == null || uuid == null) {
            return null;
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.getUUID().equals(uuid)) {
                return entity;
            }
        }
        return null;
    }
}
