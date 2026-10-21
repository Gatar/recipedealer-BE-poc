package com.gatar.recipedealer.recipe;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/recipes")
public class RecipeController {

    private final CandidateService candidates;

    public RecipeController(CandidateService candidates) {
        this.candidates = candidates;
    }

    @GetMapping("/candidates")
    public List<CandidateService.Candidate> candidates(@RequestParam(required = false) String title,
                                                       @RequestParam(required = false) String url) {
        return candidates.find(title, url);
    }
}
