/* LanguageTool, a natural language style checker 
 * Copyright (C) 2015 Daniel Naber (http://www.danielnaber.de)
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package org.languagetool.dev.wordsimilarity;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.*;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

class SimilarWordFinder {
    
    private static final int MAX_DIST = 1;
    private static final KeyboardDistance keyDistance = new GermanQwertzKeyboardDistance();

    private void createIndex(List<String> words, File indexDir) throws IOException {
        FSDirectory dir = FSDirectory.open(indexDir.toPath());
        IndexWriterConfig indexWriterConfig = new IndexWriterConfig(new StandardAnalyzer());
        System.out.println("Creating index...");
        int docs = 0;
        try (IndexWriter writer = new IndexWriter(dir, indexWriterConfig)) {
            for (String word : words) {
                Document doc = new Document();
                doc.add(new TextField("word", word, Field.Store.YES));
                writer.addDocument(doc);
                docs++;
            }
        }
        System.out.println("Index created: " + docs + " docs");
    }

    private void findSimilarWords(File indexDir, List<String> words) throws IOException {
        FSDirectory dir = FSDirectory.open(indexDir.toPath());
        try (DirectoryReader reader = DirectoryReader.open(dir)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            for (String word : words) {
                findSimilarWordsTo(reader, searcher, word);
            }
        }
    }

    private void findSimilarWordsTo(DirectoryReader reader, IndexSearcher searcher, String word) throws IOException {
        FuzzyQuery query = new FuzzyQuery(new Term("word", word), 2);  // a missing char counts as a distance of 2
        TopDocs topDocs = searcher.search(query, 10);
        //System.out.println(topDocs.totalHits + " hits for " + word);
        List<String> simWords = findSimilarWordsFor(reader, word, topDocs);
        //System.out.println(word + " -> " + String.join(", ", simWords));
        for (String simWord : simWords) {
            float dist;
            if (word.length() == simWord.length()) {
                int firstDiffPos = getDiffPos(simWord.toLowerCase(), word.toLowerCase());
                dist = keyDistance.getDistance(word.charAt(firstDiffPos), simWord.charAt(firstDiffPos));
            } else {
                dist = 0;
            }
            System.out.println(dist + "; " + word + "; " + simWord);
        }
    }

    private void findSimilarWords(File indexDir) throws IOException {
        FSDirectory dir = FSDirectory.open(indexDir.toPath());
        try (DirectoryReader reader = DirectoryReader.open(dir)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            for (int i = 0; i < reader.maxDoc(); i++) {
                Document doc = reader.document(i);
                String word = doc.get("word");
                //System.out.println(word);
                findSimilarWordsTo(reader, searcher, word);
            }
        }
    }

    private List<String> findSimilarWordsFor(DirectoryReader reader, String word, TopDocs topDocs) throws IOException {
        List<String> result = new ArrayList<>();
        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            String simWord = reader.document(scoreDoc.doc).get("word");
            //System.out.println(" sim: " + simWord);
            if (!simWord.equalsIgnoreCase(word)) {
                int firstDiffPos = getDiffPos(simWord.toLowerCase(), word.toLowerCase());
                int limit = Math.min(word.length(), simWord.length())-1;
                if (firstDiffPos > limit) {
                    //System.out.println("FILTERED: " + word + " -> " + simWord + " [" + firstDiffPos + " <= " + limit + "]");
                } else {
                    int dist = StringUtils.getLevenshteinDistance(word, simWord);
                    if (dist <= MAX_DIST) {
                        //System.out.println(word + " -> " + simWord + " [" + firstDiffPos + "]");
                        result.add(simWord);
                    }
                }
            }
        }
        // TODO: sort by keyboard distance
        return result;
    }

    private int getDiffPos(String s1, String s2) {
        int i;
        for (i = 0; i < s1.length(); i++) {
            if (i >= s2.length()) {
                return i;
            }
            if (s1.charAt(i) != s2.charAt(i)) {
                return i;
            }
        }
        return i;
    }
    
    public static void main(String[] args) throws IOException {
        SimilarWordFinder simWordFinder = new SimilarWordFinder();
        if (args.length == 1) {
            File indexDir = new File(args[0]);
            simWordFinder.findSimilarWords(indexDir);
        } else if (args.length == 2) {
            File indexDir = new File(args[1]);
            String[] words = args[0].split(",");
            simWordFinder.findSimilarWords(indexDir, Arrays.asList(words));
        } else if (args.length == 3) {
            List<String> words = FileUtils.readLines(new File(args[1]), "utf-8");
            File indexDir = new File(args[2]);
            Files.deleteIfExists(indexDir.toPath());
            simWordFinder.createIndex(words, indexDir);
        } else {
            System.out.println("Usage 1: " + SimilarWordFinder.class.getSimpleName() + " --index <wordFile> <indexFile>");
            System.out.println("Usage 2: " + SimilarWordFinder.class.getSimpleName() + " <words> <indexDir> (as created with usage 1)");
            System.out.println("             <indexDir> as created with usage 1");
            System.out.println("             <words> a comma-separated list of words to search similar words for (no spaces)");
            System.out.println("Usage 3: " + SimilarWordFinder.class.getSimpleName() + " <indexDir>");
            System.exit(1);
            
        }
    }
}
