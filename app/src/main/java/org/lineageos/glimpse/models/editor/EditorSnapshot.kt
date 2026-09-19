//
// SPDX-FileCopyrightText: 2026 The VoltageOS Project
// SPDX-License-Identifier: Apache-2.0
//

package org.lineageos.glimpse.models.editor

data class EditorSnapshot(
    val elements: List<MarkupElement> = listOf(),
    val transform: Transform = Transform(),
)
