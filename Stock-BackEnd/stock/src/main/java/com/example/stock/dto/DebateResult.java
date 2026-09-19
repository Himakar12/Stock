package com.example.stock.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DebateResult {
    private boolean debateTriggered;      // false if signals agreed and debate was skipped
    private double disagreementScore;
    private String mlAdvocatePosition;    // Gemini arguing for the ML/LSTM signal
    private String technicalAdvocatePosition; // Groq arguing for the technical signal
    private String finalSynthesis;        // Gemini's final adjudicated verdict
}