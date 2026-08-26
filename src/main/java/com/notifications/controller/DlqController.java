package com.notifications.controller;

import com.notifications.dlq.DlqService;
import com.notifications.dto.DlqEntryResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dlq")
public class DlqController {

    private final DlqService dlqService;

    public DlqController(DlqService dlqService) {
        this.dlqService = dlqService;
    }

    @GetMapping
    public List<DlqEntryResponse> list() {
        return dlqService.listAll().stream().map(DlqEntryResponse::from).toList();
    }

    /** Resets the delivery's attempt/backoff state and republishes it through the normal pipeline. */
    @PostMapping("/{deliveryId}/replay")
    public ResponseEntity<Void> replay(@PathVariable String deliveryId) {
        dlqService.replay(deliveryId);
        return ResponseEntity.accepted().build();
    }
}
