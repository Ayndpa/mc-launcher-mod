package com.mclauncher.mod.slim;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.server.packs.resources.IoSupplier;

import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * 包裹任意 {@link PackResources}，按 {@link SlimResources#isBlocked} 过滤 CLIENT_RESOURCES
 * 类型的资源。区块（SERVER_DATA）不过滤。过滤发生在 get/list 两级，
 * 因此依赖资源枚举的系统（图集拼接、模型烘焙、音效索引）都会直接“看不到”被隐藏的文件。
 */
public class SlimFilterPack implements PackResources {
    private final PackResources parent;
    private final Predicate<Identifier> blocked;

    public SlimFilterPack(PackResources parent, Predicate<Identifier> blocked) {
        this.parent = parent;
        this.blocked = blocked;
    }

    private boolean blocked(PackType type, Identifier id) {
        return type == PackType.CLIENT_RESOURCES && this.blocked.test(id);
    }

    @Override
    public PackLocationInfo location() {
        return this.parent.location();
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... path) {
        return this.parent.getRootResource(path);
    }

    @Override
    @Nullable
    public IoSupplier<InputStream> getResource(PackType type, Identifier location) {
        if (this.blocked(type, location)) {
            return null;
        }
        return this.parent.getResource(type, location);
    }

    @Override
    public void listResources(PackType type, String namespace, String directory, ResourceOutput output) {
        Predicate<Identifier> blocked = this.blocked;
        this.parent.listResources(type, namespace, directory, new ResourceOutput() {
            @Override
            public void accept(Identifier id, IoSupplier<InputStream> supplier) {
                if (type != PackType.CLIENT_RESOURCES || !blocked.test(id)) {
                    output.accept(id, supplier);
                }
            }
        });
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return this.parent.getNamespaces(type);
    }

    @Override
    @Nullable
    public <T> T getMetadataSection(MetadataSectionType<T> type) throws java.io.IOException {
        return this.parent.getMetadataSection(type);
    }

    @Override
    public Optional<KnownPack> knownPackInfo() {
        return this.parent.knownPackInfo();
    }

    @Override
    public void close() {
        this.parent.close();
    }
}
