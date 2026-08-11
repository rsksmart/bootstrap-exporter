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

import co.rsk.db.importer.BootstrapV2Format;
import co.rsk.trie.IterationElement;
import co.rsk.trie.Trie;
import co.rsk.trie.TrieStore;
import co.rsk.trie.TrieStoreImpl;
import org.ethereum.datasource.HashMapDB;
import org.ethereum.util.RLP;
import org.ethereum.util.RLPElement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the streaming v2 state export.
 *
 * <p>The exporter writes the chunked, self-describing v2 {@code bootstrap-data.bin} format
 * ({@link BootstrapV2Format}). This test pins the contract that matters: the framing round-trips, the
 * per-element (leaf) encoding is preserved exactly, and chunk boundaries always fall on element
 * boundaries (so the importer can decode each chunk with the plain RLP element machinery).
 */
class FileExporterTest {

    @TempDir
    Path tempDir;

    private TrieStore store;

    @BeforeEach
    void setUp() {
        this.store = new TrieStoreImpl(new HashMapDB());
    }

    @Test
    void nodesAndValuesSectionsRoundTripPreservingLeafEncoding() throws IOException {
        byte[] stateRoot = buildAndStoreState();

        File nodesTmp = File.createTempFile("nodes", ".tmp", tempDir.toFile());
        File valuesTmp = File.createTempFile("values", ".tmp", tempDir.toFile());

        FileExporter.StateSpill spill = FileExporter.streamStateElements(store, stateRoot, nodesTmp, valuesTmp);

        List<byte[]> nodeElements = parseSection(buildSection(BootstrapV2Format.TAG_NODES, nodesTmp, spill.nodeChunkLengths));
        List<byte[]> valueElements = parseSection(buildSection(BootstrapV2Format.TAG_VALUES, valuesTmp, spill.valueChunkLengths));

        List<byte[]> expectedNodes = legacyNodeElements(store, stateRoot);
        List<byte[]> expectedValues = legacyValueElements(store, stateRoot);

        assertTrue(expectedNodes.size() > 1, "test state should have several nodes");
        assertFalse(expectedValues.isEmpty(), "test state should have long values");
        assertElementListsEqual(expectedNodes, nodeElements);
        assertElementListsEqual(expectedValues, valueElements);
    }

    @Test
    void chunkBoundariesFallOnElementBoundaries() throws IOException {
        // many small elements + a tiny chunk cap forces multiple chunks; each must hold whole elements
        List<byte[]> elements = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            byte[] payload = new byte[7 + (i % 13)];
            for (int j = 0; j < payload.length; j++) {
                payload[j] = (byte) (i + j);
            }
            elements.add(RLP.encodeElement(payload));
        }

        File tmp = File.createTempFile("chunks", ".tmp", tempDir.toFile());
        List<Long> chunkLengths;
        try (FileExporter.SectionSpill spill = new FileExporter.SectionSpill(tmp, 64L)) {
            for (byte[] element : elements) {
                spill.write(element);
            }
            chunkLengths = spill.finish();
        }

        assertTrue(chunkLengths.size() > 1, "tiny chunk cap should produce multiple chunks");
        // every chunk decodes cleanly into whole elements, and concatenating them recovers the input
        List<byte[]> roundTripped = parseSection(buildSection(BootstrapV2Format.TAG_NODES, tmp, chunkLengths));
        assertEquals(elements.size(), roundTripped.size());
        for (int i = 0; i < elements.size(); i++) {
            assertArrayEquals(RLP.decodeFirstElement(elements.get(i), 0).getRLPData(), roundTripped.get(i));
        }
    }

    @Test
    void oversizedElementBecomesItsOwnChunk() throws IOException {
        // a single element larger than the chunk cap cannot be split, so it is emitted as its own oversized chunk
        byte[] payload = new byte[40];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        byte[] element = RLP.encodeElement(payload); // 41 bytes framed, well over the 8-byte cap below

        File tmp = File.createTempFile("oversized", ".tmp", tempDir.toFile());
        List<Long> chunkLengths;
        try (FileExporter.SectionSpill spill = new FileExporter.SectionSpill(tmp, 8L)) {
            spill.write(element);
            chunkLengths = spill.finish();
        }

        assertEquals(1, chunkLengths.size(), "one element => one chunk");
        assertTrue(chunkLengths.get(0) > 8L, "the element exceeds the cap yet is emitted as a single chunk");
        List<byte[]> roundTripped = parseSection(buildSection(BootstrapV2Format.TAG_NODES, tmp, chunkLengths));
        assertEquals(1, roundTripped.size());
        assertArrayEquals(payload, roundTripped.get(0));
    }

    @Test
    void emptySectionIsJustTagAndTerminator() throws IOException {
        File empty = File.createTempFile("empty", ".tmp", tempDir.toFile());
        List<Long> chunkLengths;
        try (FileExporter.SectionSpill spill = new FileExporter.SectionSpill(empty)) {
            chunkLengths = spill.finish();
        }
        assertTrue(chunkLengths.isEmpty());
        byte[] section = buildSection(BootstrapV2Format.TAG_VALUES, empty, chunkLengths);
        assertEquals(1 + Long.BYTES, section.length, "empty section = tag byte + zero terminator");
        assertTrue(parseSection(section).isEmpty());
    }

    // --- helpers ---

    /** Serializes a section the way FileExporter does ({@code [tag][len][bytes]...[0]}). */
    private static byte[] buildSection(int tag, File tmp, List<Long> chunkLengths) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FileExporter.writeSection(out, tag, tmp, chunkLengths);
        return out.toByteArray();
    }

    /** Parses a serialized section, returning the payloads (getRLPData) of every element in order. */
    private static List<byte[]> parseSection(byte[] section) throws IOException {
        List<byte[]> elements = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(section))) {
            int tag = in.readUnsignedByte();
            assertTrue(tag == BootstrapV2Format.TAG_NODES || tag == BootstrapV2Format.TAG_VALUES
                    || tag == BootstrapV2Format.TAG_BLOCKS, "unexpected tag " + tag);
            for (long len = in.readLong(); len != 0L; len = in.readLong()) {
                assertTrue(len > 0 && len <= BootstrapV2Format.CHUNK_MAX, "chunk length out of range: " + len);
                byte[] chunk = new byte[(int) len];
                in.readFully(chunk);
                for (RLPElement element : RLP.decode2(chunk)) {
                    elements.add(element.getRLPData());
                }
            }
            assertEquals(0, in.available(), "trailing bytes after section terminator");
        }
        return elements;
    }

    private static void assertElementListsEqual(List<byte[]> expectedPayloads, List<byte[]> actualPayloads) {
        assertEquals(expectedPayloads.size(), actualPayloads.size(), "element count mismatch");
        for (int i = 0; i < expectedPayloads.size(); i++) {
            assertArrayEquals(expectedPayloads.get(i), actualPayloads.get(i), "element " + i + " mismatch");
        }
    }

    /** The node payloads (toMessage bytes) in exporter order: root first, then non-embeddable nodes. */
    private static List<byte[]> legacyNodeElements(TrieStore store, byte[] stateRoot) {
        Trie node = store.retrieve(stateRoot).orElseThrow(NullPointerException::new);
        List<byte[]> payloads = new ArrayList<>();
        payloads.add(node.toMessage());
        Iterator<IterationElement> it = node.getInOrderIterator();
        while (it.hasNext()) {
            Trie iterating = it.next().getNode();
            if (iterating.isEmbeddable()) {
                continue;
            }
            payloads.add(iterating.toMessage());
        }
        return payloads;
    }

    private static List<byte[]> legacyValueElements(TrieStore store, byte[] stateRoot) {
        Trie node = store.retrieve(stateRoot).orElseThrow(NullPointerException::new);
        List<byte[]> payloads = new ArrayList<>();
        Iterator<IterationElement> it = node.getInOrderIterator();
        while (it.hasNext()) {
            Trie iterating = it.next().getNode();
            if (iterating.hasLongValue()) {
                payloads.add(iterating.getValue());
            }
        }
        return payloads;
    }

    /**
     * Builds a state trie that exercises every branch of the encoder: many nodes (so upper nodes are
     * non-embeddable), long values (so the values list is non-empty), and short values (so embeddable
     * nodes are skipped via {@code continue}).
     */
    private byte[] buildAndStoreState() {
        Trie trie = new Trie(store);
        for (int i = 0; i < 512; i++) {
            byte[] key = new byte[]{(byte) i, (byte) (i >> 8), (byte) (i * 31)};
            byte[] value;
            if (i % 2 == 0) {
                value = new byte[64]; // long value -> stored externally, appears in the values list
                value[0] = (byte) i;
                value[63] = (byte) (i >> 8);
            } else {
                value = new byte[]{(byte) i}; // short value -> may be embedded
            }
            trie = trie.put(key, value);
        }
        store.save(trie);
        return trie.getHash().getBytes();
    }
}
