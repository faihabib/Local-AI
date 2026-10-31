package com.localai.app.ui

data class ImageModel(
    val id: String,
    val displayName: String,
    val description: String,
    val sizeLabel: String,
    val fileName: String,
    val url: String,
    val type: String,
)

object ImageModelCatalog {

    val cpuModels = listOf(
        ImageModel(
            id = "cpu_absolute_reality",
            displayName = "Absolute Reality v1.6",
            description = "Standard .safetensors. Realistic.",
            sizeLabel = "~2.1 GB",
            fileName = "absolutereality_v16.safetensors",
            url = "https://huggingface.co/Corcelio/mobius/resolve/main/absolute_reality_v16.safetensors",
            type = "cpu",
        ),
        ImageModel(
            id = "cpu_dreamshaper_8",
            displayName = "DreamShaper 8",
            description = "Standard .safetensors. Versatile.",
            sizeLabel = "~2.1 GB",
            fileName = "dreamshaper_8.safetensors",
            url = "https://huggingface.co/Lykon/DreamShaper/resolve/main/DreamShaper_8_pruned.safetensors",
            type = "cpu",
        ),
        ImageModel(
            id = "cpu_sd15_base",
            displayName = "SD 1.5 Base",
            description = "The classic Stable Diffusion 1.5.",
            sizeLabel = "~4.2 GB",
            fileName = "v1-5-pruned-emaonly.safetensors",
            url = "https://huggingface.co/runwayml/stable-diffusion-v1-5/resolve/main/v1-5-pruned-emaonly.safetensors",
            type = "cpu",
        )
    )

    val npuModels = listOf(
        ImageModel(
            id = "npu_illustrious_v16",
            displayName = "Illustrious v16",
            description = "WAI Illustrious SDXL v16. 1024x1024.",
            sizeLabel = "~4.2 GB",
            fileName = "illustrious_v16_qnn2.28_8gen3.zip",
            url = "https://huggingface.co/xororz/sdxl-qnn/resolve/main/illustrious_v16_qnn2.28_8gen3.zip",
            type = "npu",
        ),
        ImageModel(
            id = "npu_illustrious_v16_dmd2",
            displayName = "Illustrious v16 DMD2",
            description = "Merged with DMD2 LoRA. Fast, 6 steps.",
            sizeLabel = "~4.2 GB",
            fileName = "illustrious_v16_dmd2_qnn2.28_8gen3.zip",
            url = "https://huggingface.co/xororz/sdxl-qnn/resolve/main/illustrious_v16_dmd2_qnn2.28_8gen3.zip",
            type = "npu",
        ),
        ImageModel(
            id = "npu_cyberrealistic_v10_dmd2",
            displayName = "CyberRealistic v10 DMD2",
            description = "Merged with DMD2 LoRA. Fast, 6 steps.",
            sizeLabel = "~4.2 GB",
            fileName = "cyber_realistic_v10_dmd2_qnn2.28_8gen3.zip",
            url = "https://huggingface.co/xororz/sdxl-qnn/resolve/main/cyber_realistic_v10_dmd2_qnn2.28_8gen3.zip",
            type = "npu",
        ),
    )
}
