package dev.wulnry.mossboss.hook;

import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.UUID;

// Değişiklik: Method.invoke() → MethodHandle.invokeWithArguments().
// Handle'lar tryInit() sırasında bir kez alınır ve instance field olarak saklanır.
public final class ModelEngineHook {

    private final boolean available;
    private final JavaPlugin plugin;

    private MethodHandle getBlueprintHandle;
    private MethodHandle createModeledEntityHandle;
    private MethodHandle createActiveModelHandle;
    private MethodHandle addModelHandle;
    private MethodHandle getModeledEntityHandle;
    private MethodHandle destroyHandle;

    public ModelEngineHook(JavaPlugin plugin) {
        this.plugin = plugin;
        this.available = tryInit();
        if (available) {
            plugin.getLogger().info("ModelEngine bulundu; model desteği etkinleştirildi.");
        }
    }

    private boolean tryInit() {
        if (plugin.getServer().getPluginManager().getPlugin("ModelEngine") == null) {
            return false;
        }
        try {
            Class<?> apiClass           = Class.forName("com.ticxo.modelengine.api.ModelEngineAPI");
            Class<?> blueprintClass     = Class.forName("com.ticxo.modelengine.api.model.ModelBlueprint");
            Class<?> modeledEntityClass = Class.forName("com.ticxo.modelengine.api.model.ModeledEntity");
            Class<?> activeModelClass   = Class.forName("com.ticxo.modelengine.api.model.ActiveModel");

            Method getBlueprintMethod        = apiClass.getMethod("getBlueprint", String.class);
            Method createModeledEntityMethod = apiClass.getMethod("createModeledEntity", Entity.class);
            Method createActiveModelMethod   = apiClass.getMethod("createActiveModel", blueprintClass);
            Method addModelMethod            = modeledEntityClass.getMethod("addModel", activeModelClass, boolean.class);
            Method getModeledEntityMethod    = apiClass.getMethod("getModeledEntity", UUID.class);
            Method destroyMethod             = modeledEntityClass.getMethod("destroy");

            MethodHandles.Lookup lookup = MethodHandles.lookup();
            getBlueprintHandle        = lookup.unreflect(getBlueprintMethod);
            createModeledEntityHandle = lookup.unreflect(createModeledEntityMethod);
            createActiveModelHandle   = lookup.unreflect(createActiveModelMethod);
            addModelHandle            = lookup.unreflect(addModelMethod);
            getModeledEntityHandle    = lookup.unreflect(getModeledEntityMethod);
            destroyHandle             = lookup.unreflect(destroyMethod);
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("ModelEngine API sınıfları yüklenemedi: " + e.getMessage());
            return false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public void applyModel(Entity entity, String modelId) {
        if (!available || modelId == null || modelId.isBlank()) {
            return;
        }
        try {
            Object blueprint = getBlueprintHandle.invokeWithArguments(modelId);
            if (blueprint == null) {
                plugin.getLogger().warning("ModelEngine blueprint bulunamadı: '" + modelId + "'");
                return;
            }
            Object modeledEntity = createModeledEntityHandle.invokeWithArguments(entity);
            if (modeledEntity == null) {
                return;
            }
            Object activeModel = createActiveModelHandle.invokeWithArguments(blueprint);
            addModelHandle.invokeWithArguments(modeledEntity, activeModel, true);
        } catch (Throwable t) {
            plugin.getLogger().warning("Model uygulanırken hata: " + t.getMessage());
        }
    }

    public void removeModel(Entity entity) {
        if (!available) {
            return;
        }
        try {
            Object modeledEntity = getModeledEntityHandle.invokeWithArguments(entity.getUniqueId());
            if (modeledEntity != null) {
                destroyHandle.invokeWithArguments(modeledEntity);
            }
        } catch (Throwable ignored) {
        }
    }
}
