package com.itda.problem.controller;

import com.itda.global.security.AuthenticatedUser;
import com.itda.problem.dto.RegisterProblemRequest;
import com.itda.problem.service.ProblemService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class ProblemController {
    private final ProblemService problemService;


    @PostMapping("/lectures/{lectureId}/problems")
    public ResponseEntity<Void> registerProblem(
        @PathVariable Long lectureId,
        @RequestBody RegisterProblemRequest registerProblemRequest,
        @AuthenticationPrincipal AuthenticatedUser user
        ){
        problemService.registerProblem(user, lectureId, registerProblemRequest);
        return ResponseEntity.noContent().build();

    }

}
