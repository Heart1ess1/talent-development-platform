package com.talent.platform.storage;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StorageTransferControllerTest {
  @Test
  void exposesIdempotentTicketAbandonment() {
    var tickets = mock(UploadTicketService.class);
    var controller = new StorageTransferController(tickets);
    var ticketId = UUID.randomUUID();

    var response = controller.abandon(ticketId);

    verify(tickets).abandon(ticketId);
    assertThat(response.code()).isZero();
  }
}
