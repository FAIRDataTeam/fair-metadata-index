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

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.eclipse.rdf4j.util.iterators.EmptyIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import solutions.fairdata.fdp.index.api.dto.PingDTO;
import solutions.fairdata.fdp.index.database.repository.EventRepository;
import solutions.fairdata.fdp.index.database.repository.IndexEntryRepository;
import solutions.fairdata.fdp.index.entity.IndexEntry;
import solutions.fairdata.fdp.index.entity.config.EventsConfig;
import solutions.fairdata.fdp.index.entity.events.Event;
import solutions.fairdata.fdp.index.entity.http.Exchange;
import solutions.fairdata.fdp.index.entity.http.ExchangeState;
import solutions.fairdata.fdp.index.exceptions.IncorrectPingFormatException;
import solutions.fairdata.fdp.index.utils.IncomingPingUtils;
import solutions.fairdata.fdp.index.utils.MetadataRetrievalUtils;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.time.Instant;

@Service
public class EventService {
    private static final Logger logger = LoggerFactory.getLogger(EventService.class);

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ThreadPoolTaskExecutor executor;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private IndexEntryRepository indexEntryRepository;

    @Autowired
    private IndexEntryService indexEntryService;

    @Autowired
    private EventsConfig eventsConfig;

    public Iterable<Event> getEvents(IndexEntry indexEntry) {
        // TODO: make events pagination in the future
        return eventRepository.getAllByRelatedTo(indexEntry, PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "created")));
    }

    public Iterable<Event> getEvents(String clientUrl) {
        return indexEntryService.findEntry(clientUrl).map(this::getEvents).orElse(EmptyIterator::new);
    }

    @SneakyThrows
    public Event acceptIncomingPing(HttpEntity<String> httpEntity, HttpServletRequest request) {
        var event = IncomingPingUtils.prepareEvent(httpEntity, request);
        eventRepository.save(event);
        event.execute();
        try {
            var pingDTO = objectMapper.readValue(httpEntity.getBody(), PingDTO.class);
            var indexEntry = indexEntryService.storeEntry(pingDTO);
            event.getIncomingPing().getExchange().getResponse().setCode(204);
            event.setRelatedTo(indexEntry);
            logger.info("Accepted incoming ping as a new event");
        } catch (Exception e) {
            var ex = new IncorrectPingFormatException("Could not parse PING: " + e.getMessage());
            event.getIncomingPing().getExchange().getResponse().setCode(400);
            event.getIncomingPing().getExchange().getResponse().setBody(objectMapper.writeValueAsString(ex.getErrorDTO()));
            event.setFinished(Instant.now());
            eventRepository.save(event);
            logger.info("Incoming ping has incorrect format: " + e.getMessage());
            throw ex;
        }
        event.setFinished(Instant.now());
        return eventRepository.save(event);
    }

    private void processMetadataRetrieval(Event event) {
        String clientUrl = event.getRelatedTo().getClientUrl();
        if (MetadataRetrievalUtils.shouldRetrieve(event, eventsConfig.getRetrievalRateLimitWait())) {
            indexEntryRepository.save(event.getRelatedTo());
            eventRepository.save(event);
            event.execute();

            logger.info("Retrieving metadata for " + clientUrl);
            MetadataRetrievalUtils.retrieveRepositoryMetadata(event, eventsConfig.getRetrievalTimeout());
            Exchange ex = event.getMetadataRetrieval().getExchange();
            if (ex.getState() == ExchangeState.Retrieved) {
                try {
                    logger.info("Parsing metadata for " + clientUrl);
                    var metadata = MetadataRetrievalUtils.parseRepositoryMetadata(ex.getResponse().getBody());
                    if (metadata.isPresent()) {
                        event.getMetadataRetrieval().setMetadata(metadata.get());
                        event.getRelatedTo().setCurrentMetadata(metadata.get());
                        logger.info("Storing metadata for " + clientUrl);
                        indexEntryRepository.save(event.getRelatedTo());
                    } else {
                        logger.info("Repository not found in metadata for " + clientUrl);
                        event.getMetadataRetrieval().setError("Repository not found in metadata");
                    }
                } catch (Exception e) {
                    logger.info("Cannot parse metadata for " + clientUrl);
                    event.getMetadataRetrieval().setError("Cannot parse metadata");
                }
            } else {
                logger.info("Cannot retrieve metadata for " + clientUrl + ": " + ex.getError());
            }
        } else {
            logger.info("Rate limit reached for " + clientUrl + " (skipping metadata retrieval)");
            event.getMetadataRetrieval().setError("Rate limit reached (skipping)");
        }
        event.getRelatedTo().setLastRetrievalTime(Instant.now());
        event.finish();
        eventRepository.save(event);
        indexEntryRepository.save(event.getRelatedTo());
    }

    @Async
    public void triggerMetadataRetrieval(Event triggerEvent) {
        var event = MetadataRetrievalUtils.prepareEvent(triggerEvent);
        logger.info("Triggering metadata retrieval for " + triggerEvent.getRelatedTo().getClientUrl());
        processMetadataRetrieval(event);
    }

    private void resumeUnfinishedEvents() {
        logger.info("Resuming unfinished events");
        for (Event event : eventRepository.getAllByFinishedIsNull()) {
            logger.info("Resuming event " + event.getUuid());
            processMetadataRetrieval(event);
        }
        logger.info("Finished unfinished events");
    }

    @PostConstruct
    public void startResumeUnfinishedEvents() {
        executor.submit(this::resumeUnfinishedEvents);
    }
}
