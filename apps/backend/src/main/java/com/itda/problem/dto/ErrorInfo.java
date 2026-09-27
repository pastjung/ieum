package com.itda.problem.dto;

import com.itda.problem.domain.ErrorType;

public record ErrorInfo(ErrorType errorType, Long errorCount) {}
