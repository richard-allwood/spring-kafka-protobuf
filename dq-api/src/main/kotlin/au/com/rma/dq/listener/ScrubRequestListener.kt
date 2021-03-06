/*
 * Copyright (c) 2020 Richard Allwood
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package au.com.rma.dq.listener

import au.com.rma.dq.configuration.KafkaEnvironment
import au.com.rma.dq.model.ScrubRequest
import au.com.rma.dq.model.ScrubResponse
import au.com.rma.dq.service.PhoneNumberService
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kafka.receiver.ReceiverPartition
import reactor.kafka.receiver.ReceiverRecord
import reactor.kafka.sender.KafkaSender
import reactor.kafka.sender.SenderRecord
import reactor.kafka.sender.SenderResult
import reactor.kotlin.core.publisher.toMono
import java.time.Duration.ofMillis

@Component
class ScrubRequestListener(
    val sender: KafkaSender<String, ScrubResponse>,
    val scrubber: PhoneNumberService,
    val environment: KafkaEnvironment
): TopicListener<String, ScrubRequest, ScrubResponse> {
  private val logger: Logger = LoggerFactory.getLogger(javaClass)

  override fun onAssignPartitions(partitions: Collection<ReceiverPartition>) = logger.info("onAssignPartitions {}", partitions)

  override fun onRevokePartitions(partitions: Collection<ReceiverPartition>) = logger.info("onRevokePartitions {}", partitions)

  override fun onRecord(record: ReceiverRecord<String, ScrubRequest>): Mono<ScrubResponse> {
    logger.info("onRecord({}) partition: {}, offset: {}, timestamp: {}", record.key(), record.partition(), record.offset(), record.timestamp())
    logger.info("\n${record.value()}")

    val response = ScrubResponse.newBuilder()
        .addAllPhoneNumbers(scrubber.scrubPhoneNumbers(record.value().phoneNumbersList))
        .build()

    return sendResponse(record.key(), response)
        .map { sr -> sr.correlationMetadata() }
        .toMono()
  }

  fun sendResponse(correlationKey: String, response: ScrubResponse): Flux<SenderResult<ScrubResponse>> {
    logger.info("Send Response(${correlationKey})\n${response}")
    val producerRecord = ProducerRecord(environment.dqResponseTopic, correlationKey, response)
    val event = Mono.just(SenderRecord.create(producerRecord, response))
        .delayElement(ofMillis(100))
    return sender.send(event)
  }

}