package com.mojang.rubydung.render.vk;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRDynamicRendering.*;
import static org.lwjgl.vulkan.KHRGetPhysicalDeviceProperties2.VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.*;
import static org.lwjgl.vulkan.KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.*;

/** Owns the Vulkan instance, surface, physical device, logical device and the graphics+present queue. */
public class VkContext {
    public VkInstance instance;
    public long surface;
    public VkPhysicalDevice physicalDevice;
    public VkDevice device;
    public VkQueue queue;
    public int queueFamily;
    public VkPhysicalDeviceMemoryProperties memProps;

    private long debugMessenger = VK_NULL_HANDLE;
    private final boolean validation;

    public VkContext(long window) {
        this.validation = "true".equalsIgnoreCase(System.getProperty("rd.vkValidation"));
        createInstance();
        createSurface(window);
        pickPhysicalDevice();
        createLogicalDevice();
        this.memProps = VkPhysicalDeviceMemoryProperties.malloc();
        vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProps);
    }

    private void createInstance() {
        try (MemoryStack stack = stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8("RubyDung"))
                .applicationVersion(VK_MAKE_VERSION(1, 0, 0))
                .pEngineName(stack.UTF8("RubyDung"))
                .engineVersion(VK_MAKE_VERSION(1, 0, 0))
                .apiVersion(VK_API_VERSION_1_2);

            // required surface extensions from GLFW
            PointerBuffer glfwExt = glfwGetRequiredInstanceExtensions();
            if (glfwExt == null) throw new RuntimeException("GLFW: no required Vulkan instance extensions");

            List<String> extNames = new ArrayList<>();
            for (int i = 0; i < glfwExt.capacity(); i++) extNames.add(memUTF8(glfwExt.get(i)));

            java.util.Set<String> available = availableInstanceExtensions(stack);
            boolean hasPortabilityEnum = available.contains(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);
            if (available.contains(VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME))
                extNames.add(VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME);
            if (hasPortabilityEnum)
                extNames.add(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);

            boolean useValidation = validation && hasValidationLayer(stack);
            if (useValidation) extNames.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);

            PointerBuffer pExt = stack.mallocPointer(extNames.size());
            for (String e : extNames) pExt.put(stack.UTF8(e));
            pExt.flip();

            VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .flags(hasPortabilityEnum ? VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR : 0)
                .pApplicationInfo(appInfo)
                .ppEnabledExtensionNames(pExt);

            if (useValidation) {
                PointerBuffer layers = stack.mallocPointer(1);
                layers.put(stack.UTF8("VK_LAYER_KHRONOS_validation")).flip();
                ci.ppEnabledLayerNames(layers);
                System.out.println("[vk] validation layer enabled");
            }

            PointerBuffer pInstance = stack.mallocPointer(1);
            int err = vkCreateInstance(ci, null, pInstance);
            if (err != VK_SUCCESS) throw new RuntimeException("vkCreateInstance failed: " + err);
            instance = new VkInstance(pInstance.get(0), ci);

            if (useValidation) setupDebugMessenger(stack);
        }
    }

    private java.util.Set<String> availableInstanceExtensions(MemoryStack stack) {
        java.util.Set<String> set = new java.util.HashSet<>();
        IntBuffer count = stack.mallocInt(1);
        vkEnumerateInstanceExtensionProperties((String) null, count, null);
        if (count.get(0) == 0) return set;
        VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count.get(0), stack);
        vkEnumerateInstanceExtensionProperties((String) null, count, props);
        for (int i = 0; i < props.capacity(); i++) set.add(props.get(i).extensionNameString());
        return set;
    }

    private boolean hasValidationLayer(MemoryStack stack) {        IntBuffer count = stack.mallocInt(1);
        vkEnumerateInstanceLayerProperties(count, null);
        if (count.get(0) == 0) return false;
        VkLayerProperties.Buffer props = VkLayerProperties.malloc(count.get(0), stack);
        vkEnumerateInstanceLayerProperties(count, props);
        for (int i = 0; i < props.capacity(); i++) {
            if ("VK_LAYER_KHRONOS_validation".equals(props.get(i).layerNameString())) return true;
        }
        System.out.println("[vk] validation requested but VK_LAYER_KHRONOS_validation not available; skipping");
        return false;
    }

    private void setupDebugMessenger(MemoryStack stack) {
        VkDebugUtilsMessengerCreateInfoEXT ci = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
            .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
            .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
            .pfnUserCallback((severity, type, pData, pUser) -> {
                VkDebugUtilsMessengerCallbackDataEXT data = VkDebugUtilsMessengerCallbackDataEXT.create(pData);
                System.err.println("[vk] " + data.pMessageString());
                return VK_FALSE;
            });
        LongBuffer pMessenger = stack.mallocLong(1);
        if (vkCreateDebugUtilsMessengerEXT(instance, ci, null, pMessenger) == VK_SUCCESS)
            debugMessenger = pMessenger.get(0);
    }

    private void createSurface(long window) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pSurface = stack.mallocLong(1);
            int err = glfwCreateWindowSurface(instance, window, null, pSurface);
            if (err != VK_SUCCESS) throw new RuntimeException("glfwCreateWindowSurface failed: " + err);
            surface = pSurface.get(0);
        }
    }

    private void pickPhysicalDevice() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, count, null);
            if (count.get(0) == 0) throw new RuntimeException("No Vulkan physical devices found");
            PointerBuffer devices = stack.mallocPointer(count.get(0));
            vkEnumeratePhysicalDevices(instance, count, devices);

            for (int i = 0; i < devices.capacity(); i++) {
                VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance);
                int fam = findGraphicsPresentQueue(candidate, stack);
                if (fam >= 0) {
                    physicalDevice = candidate;
                    queueFamily = fam;
                    VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
                    vkGetPhysicalDeviceProperties(candidate, props);
                    System.out.println("[vk] using device: " + props.deviceNameString());
                    return;
                }
            }
            throw new RuntimeException("No suitable Vulkan device with graphics+present queue");
        }
    }

    private int findGraphicsPresentQueue(VkPhysicalDevice dev, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(dev, count, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(dev, count, families);
        IntBuffer presentSupport = stack.mallocInt(1);
        for (int i = 0; i < families.capacity(); i++) {
            boolean graphics = (families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0;
            vkGetPhysicalDeviceSurfaceSupportKHR(dev, i, surface, presentSupport);
            if (graphics && presentSupport.get(0) == VK_TRUE) return i;
        }
        return -1;
    }

    private boolean deviceHasExtension(VkPhysicalDevice dev, String name, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkEnumerateDeviceExtensionProperties(dev, (String) null, count, null);
        VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count.get(0), stack);
        vkEnumerateDeviceExtensionProperties(dev, (String) null, count, props);
        for (int i = 0; i < props.capacity(); i++) {
            if (name.equals(props.get(i).extensionNameString())) return true;
        }
        return false;
    }

    private void createLogicalDevice() {
        try (MemoryStack stack = stackPush()) {
            VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(queueFamily)
                .pQueuePriorities(stack.floats(1.0f));

            List<String> exts = new ArrayList<>();
            exts.add(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
            exts.add(VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME);
            // portability_subset must be enabled if reported (MoltenVK)
            if (deviceHasExtension(physicalDevice, VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME, stack))
                exts.add(VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME);

            PointerBuffer pExt = stack.mallocPointer(exts.size());
            for (String e : exts) pExt.put(stack.UTF8(e));
            pExt.flip();

            // enable dynamic rendering feature
            VkPhysicalDeviceDynamicRenderingFeaturesKHR dynRender = VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES_KHR)
                .dynamicRendering(true);

            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);

            VkDeviceCreateInfo ci = VkDeviceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pNext(dynRender)
                .pQueueCreateInfos(queueInfo)
                .ppEnabledExtensionNames(pExt)
                .pEnabledFeatures(features);

            PointerBuffer pDevice = stack.mallocPointer(1);
            int err = vkCreateDevice(physicalDevice, ci, null, pDevice);
            if (err != VK_SUCCESS) throw new RuntimeException("vkCreateDevice failed: " + err);
            device = new VkDevice(pDevice.get(0), physicalDevice, ci, VK_API_VERSION_1_2);

            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, queueFamily, 0, pQueue);
            queue = new VkQueue(pQueue.get(0), device);
        }
    }

    /** Find a memory type index satisfying typeFilter and the requested property flags. */
    public int findMemoryType(int typeFilter, int properties) {
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((typeFilter & (1 << i)) != 0
                && (memProps.memoryTypes(i).propertyFlags() & properties) == properties) {
                return i;
            }
        }
        throw new RuntimeException("No suitable Vulkan memory type");
    }

    public void destroy() {
        if (memProps != null) memProps.free();
        if (device != null) vkDestroyDevice(device, null);
        if (debugMessenger != VK_NULL_HANDLE) vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, null);
        if (surface != VK_NULL_HANDLE) vkDestroySurfaceKHR(instance, surface, null);
        if (instance != null) vkDestroyInstance(instance, null);
    }
}
