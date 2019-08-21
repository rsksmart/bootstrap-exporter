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
import org.ethereum.db.IndexedBlockStore;
import org.mapdb.DB;
import org.mapdb.DBMaker;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class FileExporter{

    private static final int BLOCKS_NEEDED = 4000;

    public static void main(String[] args) throws IOException {
        String originDatabaseDir = "/database";
        KeyValueDataSource originBlockStoreDataSource = RskContext.makeDataSource("blocks", originDatabaseDir);
        MapDBBlocksIndex originBlockStoreIndex = getBlockIndex(originDatabaseDir);
        TrieStoreImpl originUnitrieStore = getTrieStore(originDatabaseDir);

        String destinationDatabaseDir = "/output/export";
        KeyValueDataSource destinationBlockStoreDataSource = RskContext.makeDataSource("blocks", destinationDatabaseDir);
        MapDBBlocksIndex destinationBlockStoreIndex = getBlockIndex(destinationDatabaseDir);
        TrieStoreImpl destinationUnitrieStore = getTrieStore(destinationDatabaseDir);

        long to = Long.valueOf(args[0]);
        long from = to - BLOCKS_NEEDED;
        List<IndexedBlockStore.BlockInfo> lastBlocks = originBlockStoreIndex.getBlocksByNumber(to);
        Optional<IndexedBlockStore.BlockInfo> bestInfo = lastBlocks.stream().filter(IndexedBlockStore.BlockInfo::isMainChain).findFirst();
        BlockFactory blockFactory = new BlockFactory(new ActivationConfig(all()));
        Block bestBlock = blockFactory.decodeBlock(originBlockStoreDataSource.get(bestInfo.get().getHash().getBytes()));

        moveBlocks(originBlockStoreDataSource, originBlockStoreIndex, destinationBlockStoreDataSource, destinationBlockStoreIndex, from, to);
        moveState(originUnitrieStore, destinationUnitrieStore, bestBlock);

        FileOutputStream fos = new FileOutputStream("/output/bootstrap-data.zip");
        ZipOutputStream zipOut = new ZipOutputStream(fos);
        File fileToZip = new File(destinationDatabaseDir);

        zipFile(fileToZip, fileToZip.getName(), zipOut);
        zipOut.close();
        fos.close();
    }

    private static TrieStoreImpl getTrieStore(String originDatabaseDir) {
        KeyValueDataSource originUnitrieDataSource = RskContext.makeDataSource("unitrie", originDatabaseDir);
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

    private static void moveBlocks(KeyValueDataSource originBlockStoreDataSource,
                                   MapDBBlocksIndex originBlockStoreIndex,
                                   KeyValueDataSource destinationBlockStoreDataSource,
                                   MapDBBlocksIndex destinationBlockStoreIndex,
                                   long from,
                                   long to) {

        System.out.printf("Moving blocks from %d to %d", from, to);
        System.out.println();
        for(int i = 0; i < to - from; i++) {
            long blockNumber = from + (i + 1);
            List<IndexedBlockStore.BlockInfo> blockInfos = originBlockStoreIndex.getBlocksByNumber(blockNumber);
            destinationBlockStoreIndex.putBlocks(blockNumber, blockInfos);
            blockInfos.forEach(bi -> destinationBlockStoreDataSource.put(bi.getHash().getBytes(),
                    originBlockStoreDataSource.get(bi.getHash().getBytes())));
        }
        destinationBlockStoreIndex.flush();
    }

    private static void moveState(TrieStoreImpl originUnitrieStore, TrieStoreImpl destinationUnitrieStore, Block block) {
        Trie node = originUnitrieStore.retrieve(block.getStateRoot());
        System.out.printf("Encoding state from block %d %s", block.getNumber(), node.getHash());
        System.out.println();

        destinationUnitrieStore.save(node);
        Iterator<Trie.IterationElement> it = node.getInOrderIterator();

        while (it.hasNext()) {
            Trie iterating = it.next().getNode();
            if (iterating.isEmbeddable()) {
                continue;
            }
            destinationUnitrieStore.save(iterating);
        }
    }

    private static Map<ConsensusRule, Long> all() {
        return EnumSet.allOf(ConsensusRule.class).stream()
                    .collect(Collectors.toMap(Function.identity(), ignored -> 0L));
    }

    private static void zipFile(File fileToZip, String fileName, ZipOutputStream zipOut) throws IOException {
        if (fileToZip.isHidden()) {
            return;
        }
        if (fileToZip.isDirectory()) {
            if (fileName.endsWith("/")) {
                zipOut.putNextEntry(new ZipEntry(fileName));
                zipOut.closeEntry();
            } else {
                zipOut.putNextEntry(new ZipEntry(fileName + "/"));
                zipOut.closeEntry();
            }

            for (File childFile : fileToZip.listFiles()) {
                zipFile(childFile, fileName + "/" + childFile.getName(), zipOut);
            }
            return;
        }

        FileInputStream fis = new FileInputStream(fileToZip);
        ZipEntry zipEntry = new ZipEntry(fileName);
        zipOut.putNextEntry(zipEntry);

        byte[] bytes = new byte[1024];
        for (int length = fis.read(bytes); length >= 0; length = fis.read(bytes)) {
            zipOut.write(bytes, 0, length);
        }
        fis.close();
    }
}