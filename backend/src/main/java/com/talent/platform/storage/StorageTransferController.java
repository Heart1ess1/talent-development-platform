package com.talent.platform.storage;

import com.talent.platform.common.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/storage")
public class StorageTransferController {
  private final UploadTicketService uploadTickets;

  public StorageTransferController(UploadTicketService uploadTickets) {
    this.uploadTickets = uploadTickets;
  }

  @GetMapping("/capabilities")
  public ApiResponse<StorageCapabilities> capabilities() {
    boolean direct = uploadTickets.directTransferEnabled();
    return ApiResponse.ok(new StorageCapabilities(direct, direct));
  }

  @DeleteMapping("/upload-tickets/{ticketId}")
  public ApiResponse<Void> abandon(@PathVariable UUID ticketId) {
    uploadTickets.abandon(ticketId);
    return ApiResponse.ok(null);
  }

  public record StorageCapabilities(boolean directUpload, boolean signedDownload) {}
}
