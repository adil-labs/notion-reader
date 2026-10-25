package com.example.notionreader.domain.settings

enum class DiscoveryBackend(val label: String) {
    AUTOMATIC("Automatic"), CPU("CPU"), GPU("GPU"), NPU("NPU");
    companion object {
        fun fromStored(value: String?): DiscoveryBackend = entries.firstOrNull { it.name == value } ?: AUTOMATIC
    }
}
