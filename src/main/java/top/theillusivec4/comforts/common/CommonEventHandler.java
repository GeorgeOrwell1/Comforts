/*
 * Copyright (C) 2017-2019  C4
 *
 * This file is part of Comforts, a mod made for Minecraft.
 *
 * Comforts is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Comforts is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Comforts.  If not, see <https://www.gnu.org/licenses/>.
 */

package top.theillusivec4.comforts.common;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Effect;
import net.minecraft.potion.EffectInstance;
import net.minecraft.util.Hand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.event.entity.player.PlayerSetSpawnEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.event.entity.player.PlayerWakeUpEvent;
import net.minecraftforge.event.entity.player.SleepingTimeCheckEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.network.PacketDistributor;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;
import top.theillusivec4.comforts.Comforts;
import top.theillusivec4.comforts.common.block.HammockBlock;
import top.theillusivec4.comforts.common.block.SleepingBagBlock;
import top.theillusivec4.comforts.common.capability.CapabilitySleepData;
import top.theillusivec4.comforts.common.network.ComfortsNetwork;
import top.theillusivec4.comforts.common.network.SPacketAutoSleep;

public class CommonEventHandler {

  public static List<EffectInstance> debuffs = new ArrayList<>();

  private static List<EffectInstance> getDebuffs() {
    List<String> configDebuffs = ComfortsConfig.SERVER.sleepingBagDebuffs.get();

    if (!configDebuffs.isEmpty()) {

      if (debuffs.isEmpty()) {

        for (String s : configDebuffs) {
          String[] elements = s.split("\\s+");
          Effect potion = ForgeRegistries.POTIONS.getValue(new ResourceLocation(elements[0]));

          if (potion == null) {
            continue;
          }

          int duration = 0;
          int amp = 0;
          try {
            duration = Math.max(1, Math.min(Integer.parseInt(elements[1]), 1600));
            amp = Math.max(1, Math.min(Integer.parseInt(elements[2]), 4));
          } catch (Exception e) {
            Comforts.LOGGER.error("Problem parsing sleeping bag debuffs in config!", e);
          }
          debuffs.add(new EffectInstance(potion, duration * 20, amp - 1));
        }
      }

      return debuffs;
    }

    return new ArrayList<>();
  }

  @SubscribeEvent
  public void onPlayerSetSpawn(PlayerSetSpawnEvent evt) {
    PlayerEntity player = evt.getPlayer();
    World world = player.getEntityWorld();
    BlockPos pos = evt.getNewSpawn();

    if (pos != null && !world.isRemote) {
      Block block = world.getBlockState(pos).getBlock();

      if (block instanceof SleepingBagBlock || block instanceof HammockBlock) {
        evt.setCanceled(true);
      }
    }
  }

  @SubscribeEvent
  public void onSleepTimeCheck(SleepingTimeCheckEvent evt) {
    World world = evt.getPlayer().getEntityWorld();
    long worldTime = world.getDayTime() % 24000L;

    evt.getSleepingLocation().ifPresent(sleepingLocation -> {
      if (world.getBlockState(sleepingLocation).getBlock() instanceof HammockBlock) {

        if (worldTime > 500L && worldTime < 11500L) {
          evt.setResult(Event.Result.ALLOW);
        } else {

          if (ComfortsConfig.SERVER.nightHammocks.get()) {
            evt.setResult(Event.Result.DEFAULT);
          } else {
            evt.setResult(Event.Result.DENY);
          }
        }
      }
    });
  }

  @SubscribeEvent
  public void onPreWorldTick(TickEvent.WorldTickEvent evt) {

    if (evt.phase == TickEvent.Phase.START && evt.world instanceof ServerWorld) {
      ServerWorld world = (ServerWorld) evt.world;
      List<ServerPlayerEntity> players = world.getPlayers();
      Boolean allPlayersSleeping = ObfuscationReflectionHelper
          .getPrivateValue(ServerWorld.class, world, "field_73068_P");

      if (allPlayersSleeping != null && allPlayersSleeping && players.stream()
          .noneMatch((player) -> !player.isSpectator() && !player.isPlayerFullyAsleep())) {
        boolean[] skipToNight = {false};

        for (PlayerEntity player : world.getPlayers()) {
          player.getBedPosition().ifPresent(bedPos -> {
            if (player.isPlayerFullyAsleep() && world.getBlockState(bedPos)
                .getBlock() instanceof HammockBlock) {

              if (world.getGameRules().getBoolean(GameRules.DO_DAYLIGHT_CYCLE)) {
                long i = world.getDayTime() + 24000L;
                long worldTime = world.getDayTime() % 24000L;

                if (worldTime > 500L && worldTime < 11500L) {
                  world.setDayTime((i - i % 24000L) - 12001L);
                }
              }

              skipToNight[0] = true;
            }
          });

          if (skipToNight[0]) {
            break;
          }
        }

        if (skipToNight[0]) {
          ObfuscationReflectionHelper
              .setPrivateValue(ServerWorld.class, world, false, "field_73068_P");
          players.stream().filter(LivingEntity::isSleeping).forEach((player) -> {
            player.wakeUpPlayer(false, false, true);
          });

          if (world.getGameRules().getBoolean(GameRules.DO_WEATHER_CYCLE)) {
            world.dimension.resetRainAndThunder();
          }
        }
      }
    }
  }

  @SubscribeEvent
  public void onPlayerWakeUp(PlayerWakeUpEvent evt) {
    PlayerEntity player = evt.getPlayer();
    World world = player.world;

    if (!world.isRemote) {
      player.getBedPosition().ifPresent(bedPos -> {
        BlockState state = world.getBlockState(bedPos);

        if (state.getBlock() instanceof SleepingBagBlock) {
          boolean broke = false;
          List<EffectInstance> debuffs = getDebuffs();

          if (!debuffs.isEmpty()) {

            for (EffectInstance effect : debuffs) {
              player.addPotionEffect(new EffectInstance(effect.getPotion(), effect.getDuration(),
                  effect.getAmplifier()));
            }
          }

          if (world.rand.nextDouble() < ComfortsConfig.SERVER.sleepingBagBreakage.get()) {
            broke = true;
            BlockPos blockpos = bedPos
                .offset(state.get(HorizontalBlock.HORIZONTAL_FACING).getOpposite());
            world.setBlockState(blockpos, Blocks.AIR.getDefaultState(), 35);
            world.setBlockState(bedPos, Blocks.AIR.getDefaultState(), 35);
            player.sendStatusMessage(
                new TranslationTextComponent("block.comforts.sleeping_bag.broke"), true);
            world.playSound(null, bedPos, SoundEvents.BLOCK_WOOL_BREAK, SoundCategory.BLOCKS, 1.0F,
                1.0F);
            player.clearBedPosition();
          }

          final boolean hasBroken = broke;
          CapabilitySleepData.getCapability(player).ifPresent(sleepdata -> {
            if (!hasBroken && sleepdata.getAutoSleepPos() != null) {
              List<ItemStack> drops = Block.getDrops(state, (ServerWorld) world, bedPos, null);
              BlockPos blockpos = bedPos
                  .offset(state.get(HorizontalBlock.HORIZONTAL_FACING).getOpposite());
              world.setBlockState(blockpos, Blocks.AIR.getDefaultState(), 35);
              world.setBlockState(bedPos, Blocks.AIR.getDefaultState(), 35);

              if (!player.abilities.isCreativeMode) {
                drops.forEach(drop -> ItemHandlerHelper
                    .giveItemToPlayer(player, drop, player.inventory.currentItem));
              }

              player.clearBedPosition();
            }
          });
        }

        CapabilitySleepData.getCapability(player).ifPresent(sleepdata -> {
          long wakeTime = world.getDayTime();
          long timeSlept = wakeTime - sleepdata.getSleepTime();

          if (timeSlept > 500L) {
            sleepdata.setWakeTime(wakeTime);
            sleepdata.setTiredTime(
                wakeTime + (long) (timeSlept / ComfortsConfig.SERVER.sleepyFactor.get()));
          }

          sleepdata.setAutoSleepPos(null);
        });
      });
    }
  }

  @SubscribeEvent
  public void onPlayerSleep(PlayerSleepInBedEvent evt) {
    PlayerEntity player = evt.getPlayer();
    CapabilitySleepData.getCapability(player).ifPresent(sleepdata -> {

      if (!player.world.isRemote && ComfortsConfig.SERVER.wellRested.get()) {
        long dayTime = player.getEntityWorld().getDayTime();

        if (sleepdata.getWakeTime() > dayTime) {
          sleepdata.setWakeTime(0);
          sleepdata.setTiredTime(0);
        }

        if (sleepdata.getTiredTime() > dayTime) {
          player.sendStatusMessage(new TranslationTextComponent("capability.comforts.not_sleepy"),
              true);
          evt.setResult(PlayerEntity.SleepResult.OTHER_PROBLEM);
        }
      }
    });
  }
}
