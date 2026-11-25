package lumi.ash.primewelcome

import net.fabricmc.api.ClientModInitializer

object PrimeWelcome : ClientModInitializer {
    override fun onInitializeClient() {
        ChatTriggers.init()
    }
}