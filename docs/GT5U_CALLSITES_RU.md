# Найденные маршруты GT5U 5.09.54.229

Все Java-файлы официального sources JAR, включая встроенные аддоны и PollutionHelper. В списке есть объявления и внутренние вызовы. Сторонние JAR полного модпака отдельно не сканировались.

| Исходник : строка | Ссылка / вызов |
|---|---|
| `bartworks/common/tileentities/classic/TileEntityHeatedWaterPump.java:191` | `.ifPresent(c -> Pollution.addPollution(c, PollutionConfig.pollutionHeatedWaterPumpSecond));` |
| `detrav/items/behaviours/BehaviourDetravToolElectricProspector.java:137` | `int pollution = Pollution.getPollution(c);` |
| `detrav/items/behaviours/BehaviourDetravToolProspector.java:388` | `return Pollution.getPollution(aWorld.getChunkFromBlockCoords(aX, aZ));` |
| `goodgenerator/blocks/tileEntity/MTELargeEssentiaSmeltery.java:601` | `Pollution.addPollution(getBaseMetaTileEntity(), reducedPollution);` |
| `gregtech/api/graphs/consumers/NodeEnergyReceiver.java:66` | `Pollution.addPollution(tWorld.getChunkFromBlockCoords(tX, tZ), GTMod.proxy.mPollutionOnExplosion);` |
| `gregtech/api/metatileentity/BaseMetaTileEntity.java:1363` | `Pollution.addPollution((TileEntity) this, GTMod.proxy.mPollutionOnExplosion);` |
| `gregtech/api/metatileentity/implementations/MTEBasicGenerator.java:215` | `Pollution.addPollution(getBaseMetaTileEntity(), getPollution() / 2);` |
| `gregtech/api/metatileentity/implementations/MTEBasicGenerator.java:234` | `Pollution.addPollution(getBaseMetaTileEntity(), getPollution() / 2);` |
| `gregtech/api/metatileentity/implementations/MTEHatchMuffler.java:124` | `if (Pollution.getPollution(getBaseMetaTileEntity()) >= GTMod.proxy.mPollutionSmogLimit) {` |
| `gregtech/api/metatileentity/implementations/MTEHatchMuffler.java:201` | `Pollution.addPollution(getBaseMetaTileEntity(), calculatePollutionReduction(pollutionAmount));` |
| `gregtech/api/metatileentity/implementations/MTEMultiBlockBase.java:1534` | `Pollution.addPollution(getBaseMetaTileEntity(), GTMod.proxy.mPollutionOnExplosion);` |
| `gregtech/api/util/scanner/ScannerHelper.java:489` | `if (Pollution.hasPollution(currentChunk)) {` |
| `gregtech/api/util/scanner/ScannerHelper.java:491` | `transComp("chunk_info_1", goldComp(formatNumber(Pollution.getPollution(currentChunk))))` |
| `gregtech/common/GTProxy.java:1614` | `Pollution.migrate(event);` |
| `gregtech/common/misc/GTMiscCommand.java:264` | `Pollution.addPollution(` |
| `gregtech/common/pollution/Pollution.java:273` | `mutatePollution(world, coord.chunkXPos, coord.chunkZPos, c -> c.setAmount(pollution), pollutedChunks);` |
| `gregtech/common/pollution/Pollution.java:381` | `mutatePollution(w, chunkX, chunkZ, d -> d.changeAmount(aPollution), null);` |
| `gregtech/common/pollution/Pollution.java:384` | `private static void mutatePollution(World world, int x, int z, Consumer<ChunkData> mutator,` |
| `gregtech/common/pollution/PollutionHelper.java:10` | `public static void furnaceAddPollutionOnUpdate(World world, int x, int z, int pollutionAmount) {` |
| `gregtech/common/pollution/PollutionHelper.java:12` | `Pollution.addPollution(world.getChunkFromBlockCoords(x, z), pollutionAmount);` |
| `gregtech/common/tileentities/boilers/MTEBoiler.java:325` | `Pollution.addPollution(getBaseMetaTileEntity(), getPollution());` |
| `gregtech/common/tileentities/boilers/MTEBoilerBronze.java:158` | `Pollution.addPollution(getBaseMetaTileEntity(), getPollution());` |
| `gregtech/common/tileentities/machines/multi/MTEAirFilterBase.java:463` | `return Pollution.getPollution(world, chunkX, chunkZ);` |
| `gregtech/common/tileentities/machines/multi/MTEAirFilterBase.java:473` | `Pollution.addPollution(world, chunkX, chunkZ, -amount);` |
| `gregtech/common/tileentities/machines/multi/MTECharcoalPit.java:91` | `Pollution.addPollution(getBaseMetaTileEntity(), mMaxProgresstime * getPollutionPerTick(null));` |
| `gregtech/common/tileentities/machines/multi/MTECokeOven.java:341` | `Pollution.addPollution(baseMetaTileEntity, GTMod.proxy.mPollutionCokeOvenPerSecond);` |
| `gregtech/mixin/mixins/early/minecraft/pollution/MixinExplosionPollution.java:34` | `Pollution.addPollution(` |
| `gregtech/mixin/mixins/early/minecraft/pollution/MixinTileEntityFurnacePollution.java:28` | `furnaceAddPollutionOnUpdate(this.worldObj, this.xCoord, this.zCoord, FurnacePollution.FURNACE.getPollution());` |
| `gregtech/mixin/mixins/late/efr/MixinTileEntityBlastFurnacePollution.java:25` | `furnaceAddPollutionOnUpdate(` |
| `gregtech/mixin/mixins/late/efr/MixinTileEntitySmokerPollution.java:25` | `furnaceAddPollutionOnUpdate(` |
| `gregtech/mixin/mixins/late/galacticraftcore/MixinGalacticraftRocketPollution.java:47` | `Pollution.addPollution(worldObj.getChunkFromBlockCoords((int) posX, (int) posZ), pollutionAmount);` |
| `gregtech/mixin/mixins/late/ic2/MixinIC2IronFurnacePollution.java:26` | `furnaceAddPollutionOnUpdate(` |
| `gregtech/mixin/mixins/late/natura/MixinNetherrackFurnacePollution.java:25` | `furnaceAddPollutionOnUpdate(` |
| `gregtech/mixin/mixins/late/railcraft/MixinRailcraftBoilerPollution.java:42` | `Pollution.addPollution(world.getChunkFromBlockCoords(this.tile.getX(), this.tile.getZ()), pollutionAmount);` |
| `gregtech/mixin/mixins/late/railcraft/MixinRailcraftCokeOvenPollution.java:36` | `Pollution.addPollution(this.worldObj.getChunkFromBlockCoords(this.xCoord, this.zCoord), pollution);` |
| `gregtech/mixin/mixins/late/railcraft/MixinRailcraftTunnelBorePollution.java:30` | `Pollution.addPollution(` |
| `gregtech/mixin/mixins/late/thaumcraft/MixinThaumcraftAlchemyFurnacePollution.java:26` | `furnaceAddPollutionOnUpdate(` |
| `gregtech/mixin/mixins/late/tinkersconstruct/MixinFurnaceLogicPollution.java:25` | `furnaceAddPollutionOnUpdate(` |
| `gtPlusPlus/xmod/gregtech/api/metatileentity/implementations/MTEHatchMufflerAdvanced.java:117` | `Pollution.addPollution(getBaseMetaTileEntity(), pollutionAmount);` |
| `gtPlusPlus/xmod/gregtech/api/metatileentity/implementations/MTEHatchMufflerAdvanced.java:276` | `int aPollutionAmount = Pollution.getPollution(getBaseMetaTileEntity());` |
| `gtPlusPlus/xmod/gregtech/common/tileentities/machines/basic/MTEAtmosphericReconditioner.java:313` | `mCurrentChunkPollution = Pollution.getPollution(getBaseMetaTileEntity());` |
| `gtPlusPlus/xmod/gregtech/common/tileentities/machines/basic/MTEAtmosphericReconditioner.java:349` | `int mCurrentChunkPollution = Pollution.getPollution(aChunk);` |
| `gtPlusPlus/xmod/gregtech/common/tileentities/machines/basic/MTEAtmosphericReconditioner.java:492` | `Pollution.addPollution(this.getBaseMetaTileEntity(), -toRemove);` |
| `gtPlusPlus/xmod/gregtech/common/tileentities/machines/basic/MTEAtmosphericReconditioner.java:565` | `Pollution.addPollution(aChunk, -toRemove);` |
| `gtPlusPlus/xmod/gregtech/common/tileentities/machines/basic/MTEPollutionCreator.java:226` | `Pollution.addPollution(getBaseMetaTileEntity(), 100000 * pollutionMultiplier);` |
| `gtPlusPlus/xmod/gregtech/common/tileentities/machines/basic/MTEPollutionCreator.java:247` | `return Pollution.getPollution(aBaseMetaTileEntity);` |
| `gtPlusPlus/xmod/gregtech/common/tileentities/machines/basic/MTEPollutionDetector.java:240` | `return Pollution.getPollution(aBaseMetaTileEntity);` |
| `tectech/thing/metaTileEntity/single/MTEDebugPollutor.java:113` | `Pollution.addPollution(aBaseMetaTileEntity, isPolluting ? pollution : -pollution);` |
