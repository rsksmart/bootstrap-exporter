/*
 * This file is part of RskJ
 * Copyright (C) 2026 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package co.rsk.core.db.bootstrap;

import co.rsk.db.importer.BootstrapImporter;
import co.rsk.db.importer.provider.BootstrapDataProvider;
import co.rsk.trie.Trie;
import co.rsk.trie.TrieStore;
import co.rsk.trie.TrieStoreImpl;
import org.ethereum.core.BlockFactory;
import org.ethereum.datasource.HashMapDB;
import org.ethereum.db.BlockStore;
import org.ethereum.util.RLP;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end cross-repo round-trip: a state trie is serialized by the real {@link FileExporter} v2
 * writer and read back by the real {@code BootstrapImporter} v2 reader (rskj-core), asserting the
 * reconstructed state matches the origin. This is the contract both repos must agree on.
 */
class BootstrapV2RoundTripTest {

    @TempDir
    Path tempDir;

    @Test
    void exporterOutputImportsBackToIdenticalState() throws IOException {
        // origin state with a mix of short (inlined) and long (externally stored) values
        TrieStore originStore = new TrieStoreImpl(new HashMapDB());
        // a list of pairs, not a Map: byte[] keys compare by reference, which would make a Map misleading
        List<Map.Entry<byte[], byte[]>> expected = new ArrayList<>();
        Trie trie = new Trie(originStore);
        for (int i = 0; i < 256; i++) {
            byte[] key = ("contract/" + i).getBytes(StandardCharsets.UTF_8);
            byte[] value = (i % 3 == 0) ? bigValue(i) : ("v" + i).getBytes(StandardCharsets.UTF_8);
            trie = trie.put(key, value);
            expected.add(Map.entry(key, value));
        }
        originStore.save(trie);
        byte[] stateRoot = trie.getHash().getBytes();

        Path binPath = writeV2File(originStore, stateRoot);

        // import the exporter's output into a fresh store
        TrieStore destinationStore = new TrieStoreImpl(new HashMapDB());
        BlockStore blockStore = mock(BlockStore.class);
        BlockFactory blockFactory = mock(BlockFactory.class);
        BootstrapDataProvider provider = mock(BootstrapDataProvider.class);
        when(provider.getBootstrapDataPath()).thenReturn(binPath);

        BootstrapImporter importer = new BootstrapImporter(
                blockStore, destinationStore, blockFactory, provider);

        importer.importData();

        Trie reconstructed = destinationStore.retrieve(stateRoot)
                .orElseThrow(() -> new AssertionError("state root missing after import"));
        assertArrayEquals(stateRoot, reconstructed.getHash().getBytes(), "state root mismatch");
        for (Map.Entry<byte[], byte[]> e : expected) {
            assertArrayEquals(e.getValue(), reconstructed.get(e.getKey()),
                    "value mismatch for " + new String(e.getKey(), StandardCharsets.UTF_8));
        }
    }

    /** Assembles a full v2 {@code bootstrap-data.bin} using the real exporter writers. */
    private Path writeV2File(TrieStore store, byte[] stateRoot) throws IOException {
        File blocksTmp = File.createTempFile("blocks", ".tmp", tempDir.toFile());
        File nodesTmp = File.createTempFile("nodes", ".tmp", tempDir.toFile());
        File valuesTmp = File.createTempFile("values", ".tmp", tempDir.toFile());

        // the importer refuses a snapshot with an empty blocks section, so emit one opaque block
        // tuple; the block bytes are decoded by a mocked BlockFactory, so their content is irrelevant
        List<Long> blockChunks;
        try (FileExporter.SectionSpill blocks = new FileExporter.SectionSpill(blocksTmp)) {
            blocks.write(RLP.encodeList(
                    RLP.encodeElement(new byte[]{1, 2, 3}),
                    RLP.encodeElement(BigInteger.ONE.toByteArray())));
            blockChunks = blocks.finish();
        }
        FileExporter.StateSpill state = FileExporter.streamStateElements(store, stateRoot, nodesTmp, valuesTmp);

        // use the production framing so the round trip exercises the real writer, not a copy of its layout
        Path binPath = tempDir.resolve("bootstrap-data.bin");
        try (OutputStream out = Files.newOutputStream(binPath)) {
            FileExporter.writeBin(out, blocksTmp, blockChunks, valuesTmp, nodesTmp, state);
        }
        return binPath;
    }

    private static byte[] bigValue(int seed) {
        byte[] v = new byte[48 + (seed % 5)]; // > 32 bytes -> long value
        for (int i = 0; i < v.length; i++) {
            v[i] = (byte) (seed + i);
        }
        return v;
    }
}
