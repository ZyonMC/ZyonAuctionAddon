rootProject.name = "ZyonAHouseAddon"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Clon local de zAuctionHouse (~/IdeaProjects/zAuctionHouse): de el sale el jar de la API ya RELOCADO.
includeBuild("../zAuctionHouse")
