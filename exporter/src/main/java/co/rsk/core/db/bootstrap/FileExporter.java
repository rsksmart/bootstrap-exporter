/*
 * This file is part of RskJ
 * Copyright (C) 2019 RSK Labs Ltd.
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

import co.rsk.RskContext;
import co.rsk.db.MapDBBlocksIndex;
import co.rsk.trie.Trie;
import co.rsk.trie.TrieStoreImpl;
import org.ethereum.config.blockchain.upgrades.ActivationConfig;
import org.ethereum.config.blockchain.upgrades.ConsensusRule;
import org.ethereum.core.Block;
import org.ethereum.core.BlockFactory;
import org.ethereum.datasource.KeyValueDataSource;
import org.ethereum.datasource.LevelDbDataSource;
import org.ethereum.db.IndexedBlockStore;
import org.ethereum.util.RLP;
import org.mapdb.DB;
import org.mapdb.DBMaker;

import java.io.*;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class FileExporter{

    private static final int BLOCKS_NEEDED = 4000;

    public static void main(String[] args) throws IOException {
        String originDatabaseDir = "/database";
        if (args.length > 1 && args[1] != null) {
            originDatabaseDir = args[1];
        }

        KeyValueDataSource originBlockStoreDataSource = LevelDbDataSource.makeDataSource(Paths.get(originDatabaseDir, "blocks"));
        MapDBBlocksIndex originBlockStoreIndex = getBlockIndex(originDatabaseDir);
        TrieStoreImpl originUnitrieStore = getTrieStore(originDatabaseDir);

        long to = Long.valueOf(args[0]);
        long from = to - BLOCKS_NEEDED;
        List<IndexedBlockStore.BlockInfo> lastBlocks = originBlockStoreIndex.getBlocksByNumber(to);
        Optional<IndexedBlockStore.BlockInfo> bestInfo = lastBlocks.stream().filter(IndexedBlockStore.BlockInfo::isMainChain).findFirst();
        BlockFactory blockFactory = new BlockFactory(new ActivationConfig(all()));
        Block bestBlock = blockFactory.decodeBlock(originBlockStoreDataSource.get(bestInfo.get().getHash().getBytes()));

        byte[] blocks = encodeBlocks(originBlockStoreDataSource, originBlockStoreIndex, from, to);
        byte[] state = encodeState(originUnitrieStore, bestBlock);
        byte[] bootstrap = RLP.encodeList(RLP.encodeElement(blocks), RLP.encodeElement(state));

        String destinationFolder = "/output/";
        if (args.length > 2 && args[2] != null){
            destinationFolder = args[2];
        }

        FileOutputStream fos = new FileOutputStream(destinationFolder + "bootstrap-data.zip");
        ZipOutputStream zipOut = new ZipOutputStream(fos);

        zipFile(new ByteArrayInputStream(bootstrap), "bootstrap-data.bin", zipOut);
        zipOut.close();
    }

    private static TrieStoreImpl getTrieStore(String originDatabaseDir) {
        KeyValueDataSource originUnitrieDataSource = LevelDbDataSource.makeDataSource(Paths.get(originDatabaseDir, "unitrie"));
        return new TrieStoreImpl(originUnitrieDataSource);
    }

    private static MapDBBlocksIndex getBlockIndex(String databaseDir) {
        File blockIndexDirectory = new File(databaseDir + "/blocks/");
        File dbFile = new File(blockIndexDirectory, "index");
        if (!blockIndexDirectory.exists()) {
            if (!blockIndexDirectory.mkdirs()) {
                throw new IllegalArgumentException(String.format(
                        "Unable to create blocks directory: %s", blockIndexDirectory
                ));
            }
        }

        DB indexDB = DBMaker.fileDB(dbFile)
                .closeOnJvmShutdown()
                .make();

        return new MapDBBlocksIndex(indexDB);
    }

    private static byte[] encodeBlocks(KeyValueDataSource originBlockStoreDataSource,
                                       MapDBBlocksIndex originBlockStoreIndex,
                                       long from,
                                       long to) {

        System.out.printf("Encoding blocks from %d to %d", from, to);
        System.out.println();
        byte[][] encodedBlocks = new byte[BLOCKS_NEEDED][];
        for(int i = 0; i < to - from; i++) {
            byte[][] encodedTuple = new byte[2][];
            long blockNumber = from + (i + 1);
            final int j = i;
            List<IndexedBlockStore.BlockInfo> blockInfos = originBlockStoreIndex.getBlocksByNumber(blockNumber);
            blockInfos.stream()
                    .filter(IndexedBlockStore.BlockInfo::isMainChain)
                    .forEach(bi -> {
                        encodedTuple[0] = RLP.encodeElement(originBlockStoreDataSource.get(bi.getHash().getBytes()));
                        encodedTuple[1] = RLP.encodeElement(bi.getCummDifficulty().getBytes());
                        encodedBlocks[j] = RLP.encodeList(encodedTuple);
                    });
        }

        return RLP.encodeList(encodedBlocks);
    }

    private static byte[] encodeState(TrieStoreImpl originUnitrieStore, Block block) {
        Trie node = originUnitrieStore.retrieve(block.getStateRoot()).get();
        System.out.printf("Encoding state from block %d %s", block.getNumber(), node.getHash());
        System.out.println();

        List<byte[]> encodedNodes = new ArrayList<>();
        List<byte[]> encodedValues = new ArrayList<>();
        byte[] nodeBytes = node.toMessage();
        encodedNodes.add(RLP.encodeElement(nodeBytes));
        Iterator<Trie.IterationElement> it = node.getInOrderIterator();

        while (it.hasNext()) {
            Trie iterating = it.next().getNode();
            if (iterating.hasLongValue()) {
                encodedValues.add(RLP.encodeElement(iterating.getValue()));
            }
            if (iterating.isEmbeddable()) {
                continue;
            }
            nodeBytes = iterating.toMessage();
            encodedNodes.add(RLP.encodeElement(nodeBytes));
        }

        return RLP.encodeList(
                RLP.encodeList(encodedNodes.toArray(new byte[encodedNodes.size()][])),
                RLP.encodeList(encodedValues.toArray(new byte[encodedValues.size()][]))
        );
    }

    private static Map<ConsensusRule, Long> all() {
        return EnumSet.allOf(ConsensusRule.class).stream()
                    .collect(Collectors.toMap(Function.identity(), ignored -> 0L));
    }

    private static void zipFile(ByteArrayInputStream data, String fileName, ZipOutputStream zipOut) throws IOException {
        ZipEntry zipEntry = new ZipEntry(fileName);
        zipOut.putNextEntry(zipEntry);

        byte[] bytes = new byte[1024];
        for (int length = data.read(bytes); length >= 0; length = data.read(bytes)) {
            zipOut.write(bytes, 0, length);
        }
        data.close();
    }
}
