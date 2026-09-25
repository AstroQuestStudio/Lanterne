package fr.clubcitrouille.lanterne.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Set;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.server.packs.resources.IoSupplier;

/**
 * Un pack de ressources non fiable, vu à travers {@link Bouclier} : identique en tout point, sauf pour
 * les chemins protégés, qu'il déclare absents (ou, pour une texture protégée, seulement si elle
 * contient de la transparence). Le gestionnaire de ressources retombe alors sur la version vanilla.
 */
final class BouclierPack implements PackResources {
    private final PackResources inner;

    BouclierPack(PackResources inner) {
        this.inner = inner;
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
        IoSupplier<InputStream> source = inner.getResource(type, id);
        if (source == null) {
            return null;
        }
        return filtre(id, source);
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        inner.listResources(type, namespace, path, (id, source) -> {
            IoSupplier<InputStream> retenu = filtre(id, source);
            if (retenu != null) {
                output.accept(id, retenu);
            }
        });
    }

    private IoSupplier<InputStream> filtre(Identifier id, IoSupplier<InputStream> source) {
        switch (Bouclier.verdict(id)) {
            case LIBRE -> {
                return source;
            }
            case CACHE -> {
                Bouclier.refuse(inner, id, "chemin protégé");
                return null;
            }
            default -> {
                byte[] octets;
                try {
                    octets = Bouclier.lire(source.get());
                } catch (IOException | RuntimeException illisible) {
                    Bouclier.refuse(inner, id, "illisible");
                    return null;
                }
                if (!Bouclier.opaque(octets)) {
                    Bouclier.refuse(inner, id, "texture transparente");
                    return null;
                }
                return () -> new ByteArrayInputStream(octets);
            }
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return inner.getNamespaces(type);
    }

    @Override
    public String packId() {
        return inner.packId();
    }

    @Override
    public Optional<KnownPack> knownPackInfo() {
        return inner.knownPackInfo();
    }

    @Override
    public PackLocationInfo location() {
        return inner.location();
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... elements) {
        return inner.getRootResource(elements);
    }

    @Override
    public <T> T getMetadataSection(MetadataSectionType<T> type) throws IOException {
        return inner.getMetadataSection(type);
    }

    @Override
    public boolean isHidden() {
        return inner.isHidden();
    }

    @Override
    public void close() {
        inner.close();
    }
}
