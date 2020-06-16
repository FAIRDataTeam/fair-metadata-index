/**
 * The MIT License
 * Copyright © 2020 https://fairdata.solutions
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package solutions.fairdata.fdp.index.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import solutions.fairdata.fdp.index.api.dto.IndexEntryDTO;
import solutions.fairdata.fdp.index.database.repository.EntryRepository;
import solutions.fairdata.fdp.index.entity.IndexEntry;

import java.time.OffsetDateTime;

@Component
public class IndexEntryService {
    private static final Logger logger = LoggerFactory.getLogger(IndexEntryService.class);

    @Autowired
    private EntryRepository repository;

    public void storeEntry(String clientUrl) {
        var entity = repository.findByClientUrl(clientUrl);
        var now = OffsetDateTime.now();

        final IndexEntry entry;
        if (entity.isPresent()) {
            logger.info("Updating timestamp of existing entry {}", clientUrl);
            entry = entity.orElseThrow();
        } else {
            logger.info("Storing new entry {}", clientUrl);
            entry = new IndexEntry();
            entry.setClientUrl(clientUrl);
            entry.setRegistrationTime(now.toString());
        }

        entry.setModificationTime(now.toString());
        repository.save(entry);
    }

    public Iterable<IndexEntry> getAllEntries() {
        return repository.findAll();
    }

    public Page<IndexEntry> getEntriesPage(Pageable pageable) {
        return repository.findAll(pageable);
    }

    public IndexEntryDTO toDTO(IndexEntry indexEntry) {
        IndexEntryDTO dto = new IndexEntryDTO();
        dto.setClientUrl(indexEntry.getClientUrl());
        dto.setRegistrationTime(OffsetDateTime.parse(indexEntry.getRegistrationTime()));
        dto.setModificationTime(OffsetDateTime.parse(indexEntry.getModificationTime()));
        return dto;
    }
}
