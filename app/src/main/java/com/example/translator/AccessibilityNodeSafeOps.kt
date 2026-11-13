package com.example.translator

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

data class WindowInspectionResult(
    val packageName: String?,
    val targetRoot: AccessibilityNodeInfo?,
    val hollowTargetRoot: AccessibilityNodeInfo? = null
)

/**
 * Operaciones seguras sobre `AccessibilityNodeInfo` y `AccessibilityWindowInfo`.
 *
 * Por qué: Agrupa las estrategias de lectura sin prefetch para árboles Litho/Viber y evita
 * duplicar lógica defensiva contra nodos reciclados o wrappers `null`/`android` de One UI.
 */
object AccessibilityNodeSafeOps {

    @SuppressLint("WrongConstant")
    fun safeRootInActiveWindow(
        service: AccessibilityService,
        onFetchMode: (String) -> Unit = {}
    ): AccessibilityNodeInfo? {
        try {
            val direct = service.rootInActiveWindow
            if (direct != null) {
                onFetchMode("d")
                return direct
            }
            // Por qué: En árboles Litho profundos (Messenger), el prefetch híbrido puede superar el límite Binder;
            // pedir el nodo raíz sin prefetch (0) o ininterrumpible recupera la jerarquía sin bloqueos.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val noPrefetch = service.getRootInActiveWindow(0)
                if (noPrefetch != null) {
                    onFetchMode("0")
                    return noPrefetch
                }
                val uninterruptible = service.getRootInActiveWindow(AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE)
                if (uninterruptible != null) {
                    onFetchMode("u")
                    return uninterruptible
                }
            }
        } catch (_: Exception) {
            // Ignorar nodo/ventana reciclada durante transición
        }
        return null
    }

    @SuppressLint("WrongConstant")
    fun safeWindowRoot(
        window: AccessibilityWindowInfo?,
        onFetchMode: (String) -> Unit = {}
    ): AccessibilityNodeInfo? {
        if (window == null) return null
        try {
            val direct = window.root
            if (direct != null) {
                onFetchMode("d")
                return direct
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val noPrefetch = window.getRoot(0)
                if (noPrefetch != null) {
                    onFetchMode("0")
                    return noPrefetch
                }
                val uninterruptible = window.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE)
                if (uninterruptible != null) {
                    onFetchMode("u")
                    return uninterruptible
                }
            }
        } catch (_: Exception) {
            // Ignorar ventana reciclada durante transición
        }
        return null
    }

    @SuppressLint("WrongConstant")
    fun safeChild(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? {
        try {
            val direct = node.getChild(index)
            if (direct != null) return direct
            // Por qué: Cuando la raíz se obtuvo con estrategia 0 (sin prefetch), getChild(i) por defecto
            // puede devolver null salvo que se solicite explícitamente el hijo sin prefetch.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val noPrefetch = node.getChild(index, 0)
                if (noPrefetch != null) return noPrefetch
            }
        } catch (_: Exception) {
            // Ignorar nodo reciclado
        }
        return null
    }

    /**
     * Inspecciona una raíz de ventana y desciende hasta profundidad 4 cuando el contenedor raíz
     * reporta `packageName == null` o `"android"` (wrappers `DecorView`/`FrameLayout` en One UI).
     *
     * Por qué: Unifica la atribución de paquete entre la resolución de primer plano y la captura
     * de raíz, evitando descartar ventanas de Viber o Messenger envueltas en contenedores del sistema.
     */
    fun inspectWindowPackageAndRoot(
        root: AccessibilityNodeInfo?,
        enabledTargetPackages: Set<String>,
        selfPackage: String,
        requiredTargetPackage: String? = null
    ): WindowInspectionResult {
        if (root == null) return WindowInspectionResult(packageName = null, targetRoot = null)
        val rootPkg = try { root.packageName?.toString() } catch (_: Exception) { null }
        val rootChildren = try { root.childCount } catch (_: Exception) { 0 }

        fun matchesTarget(pkg: String?): Boolean {
            if (pkg.isNullOrBlank()) return false
            if (requiredTargetPackage != null) return pkg == requiredTargetPackage
            return enabledTargetPackages.contains(pkg) &&
                !TargetAppCatalog.isBlockedSensitivePackage(pkg, selfPackage)
        }

        if (!rootPkg.isNullOrBlank() && rootPkg != "android") {
            return if (matchesTarget(rootPkg)) {
                if (rootChildren > 0) {
                    WindowInspectionResult(packageName = rootPkg, targetRoot = root)
                } else {
                    WindowInspectionResult(packageName = rootPkg, targetRoot = null, hollowTargetRoot = root)
                }
            } else {
                WindowInspectionResult(packageName = rootPkg, targetRoot = null)
            }
        }

        // Por qué: Contenedores raíz `null` o `"android"` pueden envolver la jerarquía del chat 2..4 niveles abajo;
        // usamos BFS acotado con safeChild para atribuir el paquete sin saturar IPC Binder.
        val queue = java.util.ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        var firstNonAndroidPkg: String? = rootPkg
        var bestTargetWithChildren: AccessibilityNodeInfo? = null
        var bestTargetHollow: AccessibilityNodeInfo? = null
        var bestTargetPkg: String? = null

        while (queue.isNotEmpty() && visited < 24) {
            val (curr, depth) = queue.removeFirst()
            visited++
            val currPkg = try { curr.packageName?.toString() } catch (_: Exception) { null }
            val currCh = try { curr.childCount } catch (_: Exception) { 0 }

            if (!currPkg.isNullOrBlank() && currPkg != "android") {
                if (firstNonAndroidPkg.isNullOrBlank() || firstNonAndroidPkg == "android") {
                    firstNonAndroidPkg = currPkg
                }
                if (matchesTarget(currPkg)) {
                    if (bestTargetPkg == null) bestTargetPkg = currPkg
                    if (currCh > 0 && bestTargetWithChildren == null) {
                        bestTargetWithChildren = curr
                        bestTargetPkg = currPkg
                        break
                    } else if (bestTargetHollow == null) {
                        bestTargetHollow = curr
                    }
                }
            }

            if (depth >= 4) continue
            val limit = minOf(currCh, 6)
            for (i in 0 until limit) {
                val child = safeChild(curr, i) ?: continue
                queue.add(child to (depth + 1))
            }
        }

        return WindowInspectionResult(
            packageName = bestTargetPkg ?: firstNonAndroidPkg,
            targetRoot = bestTargetWithChildren,
            hollowTargetRoot = bestTargetHollow
        )
    }

    fun isIgnoredWidgetClass(className: String): Boolean {
        return className.contains("Image", ignoreCase = true) ||
            className.contains("CheckBox", ignoreCase = true) ||
            className.contains("Switch", ignoreCase = true) ||
            className.contains("SeekBar", ignoreCase = true) ||
            className.contains("ProgressBar", ignoreCase = true) ||
            className.contains("EditText", ignoreCase = true)
    }

    fun isScrollableOrToolbarClass(node: AccessibilityNodeInfo, className: String): Boolean {
        return node.isScrollable ||
            className.contains("RecyclerView", ignoreCase = true) ||
            className.contains("ListView", ignoreCase = true) ||
            className.contains("ScrollView", ignoreCase = true) ||
            className.contains("ViewPager", ignoreCase = true) ||
            className.contains("Toolbar", ignoreCase = true)
    }

    /**
     * Busca el primer subnodo textual legible de forma segura ante nodos reciclados y árboles profundos.
     */
    fun findFirstTextChild(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 24) return null
        try {
            val ch = node.childCount
            val hasDirectOrDescText = !node.text.isNullOrBlank() || (ch <= 3 && !node.contentDescription.isNullOrBlank())
            if (!node.isEditable && hasDirectOrDescText) {
                val cls = node.className?.toString() ?: ""
                if (!isIgnoredWidgetClass(cls)) {
                    return node
                }
            }
            for (i in 0 until ch) {
                val child = safeChild(node, i) ?: continue
                val found = findFirstTextChild(child, depth + 1)
                if (found != null) return found
            }
        } catch (_: Exception) {
            // Ignorar nodos reciclados al explorar un mensaje tocado
        }
        return null
    }

    // Por qué: Cuando Litho (Messenger) o ChatMessageCell (Telegram) aplana la fila del mensaje a todo
    // el ancho de pantalla, reduce un 4% por lado para superar el umbral geométrico de burbuja.
    fun normalizeFullWidthMessageBounds(
        rect: Rect,
        screenWidth: Int,
        screenHeight: Int,
        density: Float
    ) {
        if (rect.width() >= (screenWidth * 0.94f).toInt() && rect.height() <= (screenHeight * 0.45f).toInt()) {
            val horizontalMargin = maxOf((16 * density).toInt(), (screenWidth * 0.04f).toInt())
            rect.left = (rect.left + horizontalMargin).coerceAtMost(rect.right - 24)
            rect.right = (rect.right - horizontalMargin).coerceAtLeast(rect.left + 24)
        }
    }
}

