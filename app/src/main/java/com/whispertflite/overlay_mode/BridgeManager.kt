package com.whispertflite.overlay_mode

/**
 * BridgeManager - Simple bridge between FloatingOverlayService and TextInjectorService
 * Only used for text injection now (visibility controlled by starting/stopping service)
 */
object BridgeManager {
    
    private var textInjectorService: TextInjectorService? = null
    private var floatingOverlayService: FloatingOverlayService? = null
    
    fun registerTextInjector(service: TextInjectorService) {
        textInjectorService = service
    }
    
    fun unregisterTextInjector() {
        textInjectorService = null
    }
    
    fun registerOverlayService(service: FloatingOverlayService) {
        floatingOverlayService = service
    }
    
    fun unregisterOverlayService() {
        floatingOverlayService = null
    }
    
    /**
     * Inject text into focused text field
     */
    fun injectText(text: String): Boolean {
        return textInjectorService?.injectText(text) ?: false
    }
}
