package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.common.response.PageResponse;
import com.kashmirmenu.app.dto.response.FeedbackEntryResponse;
import com.kashmirmenu.app.dto.response.FeedbackSummaryResponse;
import com.kashmirmenu.app.service.FeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** What guests said about their meals. Management only: comments can be blunt, and are private to the restaurant. */
@RestController
@RequestMapping("/api/v1/feedback")
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER')")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    @Operation(summary = "Feedback, newest first. maxRating=2 shows only the low ratings")
    @GetMapping
    public PageResponse<FeedbackEntryResponse> list(
            @RequestParam(required = false) Integer maxRating,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return feedbackService.list(maxRating, page, size);
    }

    @Operation(summary = "Average, distribution and share of low ratings over the last N days (default 30, at most 365)")
    @GetMapping("/summary")
    public FeedbackSummaryResponse summary(@RequestParam(required = false) Integer days) {
        return feedbackService.summary(days);
    }
}
