ISSUE PILOT
Detailed Recommendation Ranking Algorithm
Technical Design Document

Purpose: Define, explain, calculate, and implement the complete issue-ranking pipeline used by Issue Pilot.

Item	Value
Project	Issue Pilot
Component	Personalized Open-Source Issue Recommendation and Ranking
Ranking Model	Weighted multi-factor scoring model
Primary Output	Final issue score from 0 to 100
Current Factors	Skill Match, Difficulty Match, Responsiveness, Repository Health, Feedback, Freshness
Initial Weights	35%, 25%, 15%, 10%, 10%, 5%
Status of Weights	Initial design heuristics; to be empirically calibrated


 Table of Contents
1.	1. Purpose and Scope
2.	2. Ranking Philosophy
3.	3. End-to-End Ranking Pipeline
4.	4. Data Model and Notation
5.	5. Stage 0 — Eligibility Filtering
6.	6. Stage 1 — Contributor Skill Profile
7.	7. Stage 2 — Issue Technology Relevance
8.	8. Stage 3 — Skill Match (S)
9.	9. Stage 4 — Difficulty Match (D)
10.	10. Stage 5 — Maintainer Responsiveness (R)
11.	11. Stage 6 — Repository Health (H)
12.	12. Stage 7 — Feedback / Personalization (F)
13.	13. Stage 8 — Freshness (N)
14.	14. Stage 9 — Missing Data and Cold-Start Handling
15.	15. Stage 10 — Final Weighted Score
16.	16. Complete Worked Example
17.	17. Ranking, Tie-Breaking, and Candidate Selection
18.	18. Live Verification
19.	19. Failure and Exception Handling
20.	20. Pseudocode
21.	21. Data Storage and Auditability
22.	22. Evaluation and Calibration
23.	23. Security, Fairness, and Abuse Considerations
24.	24. Implementation Recommendations
25.	25. Viva-Ready Explanation
26.	26. Summary of Formulas

 1. Purpose and Scope
This document defines the recommendation ranking algorithm for Issue Pilot from first principles. It explains what information is collected, how raw evidence is transformed into normalized scores, how contributor-specific and issue-specific signals are combined, how missing data is handled, and how the final score is produced before live verification.
The algorithm is intended to answer a practical question: for a particular contributor and a particular open-source issue, how suitable is this issue compared with other eligible issues?
The model is deliberately explainable. Every final score can be decomposed into six components, and each component can be traced back to measurable evidence. This is preferable for a college project because the recommendation can be justified to a user or examiner instead of appearing as an opaque prediction.
1.1 What this document does not claim
•	The initial numerical weights are design choices, not scientifically proven optimal weights.
•	A skill score such as Python = 90 is a normalized confidence/evidence score, not a literal percentage of expertise.
•	The initial thresholds and heuristic formulas should be treated as configurable parameters.
•	The model should eventually be calibrated against observed recommendation outcomes.
•	Unavailable information must not automatically be interpreted as a bad score.
2. Ranking Philosophy
Issue Pilot uses a two-level scoring architecture. First, it computes meaningful component scores on a 0–100 scale. Second, it applies the importance weight of each component to obtain the final score.
Level 1: Calculate component scores
    Skill Match       S = 0–100
    Difficulty Match  D = 0–100
    Responsiveness    R = 0–100
    Repository Health H = 0–100
    Feedback          F = 0–100
    Freshness         N = 0–100

Level 2: Apply ranking weights
    FinalScore =
        0.35S + 0.25D + 0.15R + 0.10H + 0.10F + 0.05N
Ranking Parameter	Weight
Skill Match (S)	35%
Difficulty Match (D)	25%
Maintainer Responsiveness (R)	15%
Repository Health (H)	10%
Feedback / Personalization (F)	10%
Freshness (N)	5%
The six ranking parameters are normalized to a 0–100 component score and then combined using these initial design weights. The weights total 100% and are configurable for later empirical calibration.
2.1 Why six parameters?
Parameter	Question answered	Primary evidence
Skill Match (S)	Can this contributor technically work on this issue?	Contributor evidence + issue requirements
Difficulty Match (D)	Is the issue complexity appropriate for this contributor?	Contributor capability + issue difficulty
Responsiveness (R)	Will maintainers likely respond/review?	Repository/maintainer interaction history
Repository Health (H)	Is the project active and healthy?	Repository activity and maintenance signals
Feedback (F)	Has this contributor historically preferred/completed similar issues?	Issue Pilot user history
Freshness (N)	Is this particular issue currently active/relevant?	Issue age and recent activity

These signals can correlate. For example, a healthy repository may also be responsive. The implementation should therefore use distinct feature sets for each component and avoid counting exactly the same raw signal twice.
3. End-to-End Ranking Pipeline
Source ingestion
    ↓
Normalize heterogeneous issue data
    ↓
Eligibility filtering
    ↓
Contributor profile construction
    ↓
Issue technology + difficulty analysis
    ↓
Calculate S, D, R, H, F, N
    ↓
Handle unavailable components
    ↓
Apply weighted final score
    ↓
Sort eligible issues
    ↓
Take top 10–20 candidates
    ↓
Live verification
    ↓
Discard invalid candidates and backfill
    ↓
Return final top 5 recommendations
3.1 Why filtering occurs before scoring
Some issues should never be recommended regardless of their numerical score. Examples include closed issues, issues that are no longer available, or issues already assigned where assignment semantics are supported. Eligibility is therefore a gate, not a ranking factor.
4. Data Model and Notation
Symbol	Meaning	Range
u	Contributor/user	Entity
I	Issue	Entity
t	Technology	Entity
S	Skill Match	0–100
D	Difficulty Match	0–100
R	Responsiveness	0–100
H	Repository Health	0–100
F	Feedback / Personalization	0–100
N	Freshness	0–100
w_i	Weight of a component	0–1
skill(u,t)	Contributor skill confidence for technology t	0–100
rel(I,t)	Issue relevance weight of technology t	0–1

5. Stage 0 — Eligibility Filtering
Before calculating the ranking score, Issue Pilot checks whether an issue is eligible.
•	Issue is still open, where the source exposes status.
•	Issue is still available at the source URL.
•	Issue is not already assigned where assignment semantics are supported.
•	Issue belongs to a repository that is not blocked or excluded by policy.
•	Required source metadata is sufficiently valid for ranking.
•	Duplicate or stale records are removed or merged according to repository/native issue identity.
5.1 Crowding / competition
Crowding is intentionally not a major fixed-weight component. Assignment status is treated as an eligibility condition where reliable assignment data exists. Visible competition may be used as a warning or tie-breaker, because competition information is inconsistent across GitHub, GitLab, Codeberg/Gitea, and Bugzilla.
6. Stage 1 — Contributor Skill Profile
The contributor skill profile is built from evidence. GitHub is one important source, but it is not the definition of skill. A contributor may have evidence from GitLab, another supported contribution platform, a resume, portfolio, questionnaire, or previous Issue Pilot outcomes.
Evidence sources
    GitHub repositories / languages
    GitHub commits
    Pull requests
    Merged pull requests
    Recent contribution activity
    Other supported contribution platforms
    Resume / portfolio
    Onboarding questionnaire
    Previous Issue Pilot contribution outcomes
        ↓
Evidence normalization
        ↓
Technology skill profile
    Python = 90
    FastAPI = 80
    MongoDB = 70
    React = 50
    Java = 20
6.1 Raw evidence versus normalized evidence
Raw GitHub data such as 8 Python repositories, 120 Python commits, and 15 Python PRs are not directly comparable because they have different scales. Each raw feature is therefore converted to a 0–100 evidence score before the features are combined.
Raw evidence	Example raw value	Normalized example
Python repositories	8	95/100
Python commits	120	90/100
Python PRs	15	85/100
Merged Python PRs	8	88/100
Recent Python activity	High	92/100

The normalized numbers above are illustrative. The implementation should define explicit thresholds or a continuous normalization function and keep those thresholds configurable.
6.2 Example technology skill calculation
PythonSkill = 0.20(95) + 0.30(90) + 0.25(85) + 0.15(88) + 0.10(92)
PythonSkill = 19 + 27 + 21.25 + 13.20 + 9.20 = 89.65 ≈ 90
The resulting Python = 90 is a contributor-side skill score. It is not an issue requirement and it is not the final ranking score.
6.3 Evidence confidence
Skill strength and evidence confidence should be conceptually separated. A self-reported 'Advanced Python' answer may produce a useful initial skill estimate but should have lower confidence than repeated, recent, successful contribution evidence. Confidence can be stored separately and used to decide how strongly a weak evidence source should influence the profile.
7. Stage 2 — Issue Technology Relevance
For each issue, Issue Pilot identifies technologies involved in the issue and estimates how important each technology is to solving that issue. These are issue-side relevance weights.
Example issue: 'Fix FastAPI authentication and MongoDB connection handling in the Python backend.'
Technology	Raw relevance example	Normalized relevance
FastAPI	5	0.50
Python	3	0.30
MongoDB	2	0.20

The raw relevance score is an explainable heuristic representing evidence strength. For example, a technology central to the issue may receive 5, a strongly required technology 4, a normal explicit requirement 3, an indirect dependency 2, weak evidence 1, and absent evidence 0.
Total relevance = 5 + 3 + 2 = 10
FastAPI relevance = 5/10 = 0.50
Python relevance = 3/10 = 0.30
MongoDB relevance = 2/10 = 0.20
Therefore 0.50, 0.30, and 0.20 are not manually assigned for every issue. They are normalized issue-specific relevance weights generated from evidence in the title, body, repository technology, affected files/components, labels as supporting evidence, and optionally related PRs/commits or NLP/embedding analysis.
8. Stage 3 — Skill Match (S)
Skill Match answers: 'How well does this contributor's demonstrated technical profile match the technologies required by this particular issue?'
S = [Σ(skill(u,t) × rel(I,t))] for all issue technologies t
When relevance weights sum to 1 and skill scores are already on 0–100, the formula directly produces a 0–100 score.
Technology	Contributor skill	Issue relevance	Contribution
Python	90	0.30	90 × 0.30 = 27
FastAPI	80	0.50	80 × 0.50 = 40
MongoDB	70	0.20	70 × 0.20 = 14

S = 27 + 40 + 14 = 81
Therefore Skill Match = 81/100.
The final ranking weight of Skill Match is separate: 0.35 × S. For S = 81, Skill Match contributes 28.35 points to the final score.
9. Stage 4 — Difficulty Match (D)
Difficulty Match answers: 'Is the complexity of this issue appropriate for the contributor's demonstrated capability?' It is different from Skill Match. A contributor can know the right technology but still be inexperienced with a large or complex change.
9.1 Contributor capability
Capability can be estimated from evidence such as:
•	Number of successful/merged contributions.
•	Complexity and size of previous changes.
•	Number of files/components changed in successful work.
•	Experience across repositories or projects.
•	Contribution duration and recent activity.
•	Historical success on issues of different difficulty.
Capability = 0.25(MergedWork) + 0.20(ChangeComplexity) + 0.25(SuccessHistory) + 0.10(ExperienceDuration) + 0.20(ProjectComplexity)
All inputs are normalized to 0–100 before the weighted combination. Example: capability = 76.
9.2 Issue difficulty
Issue difficulty should be repository-relative rather than treated as an absolute universal number.
•	Historical resolution time relative to repository baseline.
•	Number of discussion participants/comments.
•	Scope and number of affected components/files.
•	Dependency or architectural impact.
•	Technical complexity in the issue description.
•	Historical difficulty of similar resolved issues.
•	Labels such as good-first-issue as weak supporting evidence, not ground truth.
9.2.1 Issue Difficulty Calculation
Issue Difficulty (ID) is a normalized 0–100 estimate of how difficult the issue is to implement. It is calculated from multiple evidence sources so that difficulty is not determined by a single noisy signal. The initial model uses five components:
Component	Weight	What it measures
Scope / Change Size (SC)	25%	How much of the codebase or how many components/files are likely to change.
Technical Complexity (TC)	25%	How technically difficult the underlying problem is, including logic, security, concurrency, database, or architecture complexity.
Historical Effort (HE)	25%	How much effort similar issues required in this repository, relative to the repository baseline.
Dependency / Architectural Impact (DI)	15%	How many important system components, interfaces, or architectural layers can be affected.
Coordination Complexity (CC)	10%	How much coordination is required across maintainers, components, repositories, or compatibility boundaries.
Initial Issue Difficulty formula:
ID = 0.25(SC) + 0.25(TC) + 0.25(HE) + 0.15(DI) + 0.10(CC)
All five inputs are normalized to 0–100 before the weighted combination. Therefore, the resulting Issue Difficulty score also lies on a 0–100 scale, where 0 represents very low difficulty and 100 represents very high difficulty.
9.2.2 Meaning of the five difficulty components
•	Scope / Change Size (SC): Estimates how much code or how many modules are likely to be affected. A typo or isolated documentation correction may be near the low end, while a change spanning frontend, backend, and database layers may be much higher.
•	Technical Complexity (TC): Captures the inherent technical challenge. Signals may include algorithms, business logic, authentication/security, concurrency, database migration, performance, distributed systems, or architectural complexity.
•	Historical Effort (HE): Uses resolved issues from the same repository. Resolution time and related effort proxies are interpreted relative to the repository baseline, so a three-day issue is not treated identically in every repository.
•	Dependency / Architectural Impact (DI): Measures how broadly the change can affect important system parts, APIs, data models, services, or architectural boundaries.
•	Coordination Complexity (CC): Measures the amount of coordination likely to be required, such as multiple maintainers, cross-repository dependencies, API compatibility, migration planning, or discussion among several components/teams.
9.2.3 Worked example
Suppose an issue is estimated as follows: SC = 70, TC = 75, HE = 65, DI = 80, and CC = 50.
ID = 0.25(70) + 0.25(75) + 0.25(65) + 0.15(80) + 0.10(50)
= 17.50 + 18.75 + 16.25 + 12.00 + 5.00 = 69.50 ≈ 70
Therefore, the estimated Issue Difficulty is 70/100. This value is then compared with the contributor capability score to calculate Difficulty Match (D).
Labels such as good-first-issue, comments, and issue type are supporting evidence only. They do not directly define difficulty and should not be treated as ground truth.
The weights and normalization thresholds are initial design heuristics and should remain configurable. They can later be calibrated using historical issue outcomes and ranking evaluation metrics.
9.3 Initial compatibility formula
D = 100 − |ContributorCapability − IssueDifficulty|
Example: contributor capability = 76 and issue difficulty = 72.
D = 100 − |76 − 72| = 96
The formula is intentionally simple and explainable for the initial implementation. A later version can use a calibrated nonlinear or piecewise function if evaluation shows that a simple absolute-distance model is insufficient.
10. Stage 5 — Maintainer Responsiveness (R)
Responsiveness measures how likely maintainers of the repository are to respond to contributor activity. It is not the user's response speed.
•	Historical issue response time.
•	Percentage of issues receiving maintainer response.
•	Pull-request review responsiveness.
•	Recent maintainer activity.
Signal	Normalized example	Weight
Response-time score	90	0.30
Response-rate score	90	0.30
PR-review score	85	0.25
Recent maintainer activity	90	0.15

R = 0.30(90) + 0.30(90) + 0.25(85) + 0.15(90) = 88.75 ≈ 89
Lower response time should map to a higher normalized score. Source-specific limitations must be respected; if a source does not expose reliable interaction history, the component is marked unavailable rather than forced to zero.
11. Stage 6 — Repository Health (H)
Repository Health measures the overall vitality and maintenance state of the project. It is different from Responsiveness: a project can have many commits but slow maintainer responses, or excellent maintainer responsiveness in a small project.
•	Recent commit activity.
•	Pull-request activity.
•	Issue activity and closure activity.
•	Number of active contributors.
•	Recent releases or equivalent project maintenance signals.
Signal	Score	Weight
Recent commits	90	0.25
PR activity	80	0.20
Issue activity	85	0.20
Active contributors	75	0.20
Recent releases	80	0.15

H = 0.25(90) + 0.20(80) + 0.20(85) + 0.20(75) + 0.15(80) = 82.5 ≈ 83
12. Stage 7 — Feedback / Personalization (F)
Feedback is contributor-specific learning from Issue Pilot's own recommendation history. It answers: 'Given what this user has previously accepted, rejected, started, and completed, how suitable does this type of issue appear to be for them?'
12.1 Required event data
•	Recommendation shown.
•	Recommendation accepted or rejected.
•	Whether an accepted issue was started, where available.
•	Whether the contribution was completed.
•	Snapshot of issue characteristics at recommendation time.
•	Timestamp of the feedback event.
12.2 Acceptance score
For a set of similar previously recommended issues:
AcceptanceScore A = Accepted / (Accepted + Rejected) × 100
Example: 12 accepted and 8 rejected.
A = 12 / (12 + 8) × 100 = 60
Therefore Acceptance Score = 60.
12.3 Completion score
Completion is stronger evidence than acceptance because accepting an issue does not guarantee that work was completed.
CompletionScore C = Completed / Accepted × 100
Example: 9 completed out of 12 accepted.
C = 9 / 12 × 100 = 75
Therefore Completion Score = 75.
12.4 Similarity-to-successful-history score
The current issue is compared with issues the contributor previously completed successfully. Similarity can use technology overlap, issue type, difficulty proximity, repository/domain information, labels as supporting features, and semantic text similarity.
Example SimilarityScore = 90
12.5 Feedback formula
F = 0.30A + 0.40C + 0.30Similarity
Using the consistent example above:
F = 0.30(60) + 0.40(75) + 0.30(90)
F = 18 + 30 + 27 = 75
Therefore Feedback = 75/100.
Completion receives the highest sub-weight because it is a stronger behavioral signal than simply accepting a recommendation.
12.6 Cold-start feedback
A new user may have no recommendation history. In that case Feedback is unavailable, not zero. A zero would incorrectly mean that the system believes the user is unsuitable. The final ranking therefore renormalizes the weights of the available components.
13. Stage 8 — Freshness (N)
Freshness measures how current and active the particular issue is. It is not personalized to the user.
13.1 Inputs
•	Issue age.
•	Time since last activity.
•	Recent comments or updates.
•	Recent related activity where available.
13.2 Initial age normalization
Issue age	Illustrative age score
≤ 1 day	100
2–3 days	90
4–7 days	80
8–14 days	65
15–30 days	50
31–90 days	30
> 90 days	15

13.3 Initial recent-activity normalization
Time since last activity	Illustrative activity score
≤ 1 day	100
2–3 days	90
4–7 days	80
8–14 days	60
15–30 days	40
> 30 days	20

13.4 Freshness formula
N = 0.40(AgeScore) + 0.60(ActivityScore)
Example: issue age score = 80 and recent activity score = 100.
N = 0.40(80) + 0.60(100) = 32 + 60 = 92
Therefore Freshness = 92/100.
A continuous decay function can replace bucket thresholds later, for example AgeScore = 100e^(−λ × AgeDays), with λ calibrated from observed issue lifecycle data. The bucket method is easier to explain and implement in the initial project.
14. Stage 9 — Missing Data and Cold-Start Handling
Issue Pilot operates across heterogeneous sources. GitHub, GitLab, Codeberg/Gitea, and Bugzilla do not expose exactly the same fields. The system must distinguish 'unknown' from 'bad'.
14.1 Core rule
Unavailable data ≠ score of 0
If a component cannot be computed reliably, mark it unavailable and calculate the final score using the available weights only.
EffectiveScore = Σ(AvailableScore_i × Weight_i) / Σ(AvailableWeight_i)
Equivalent implementation form:
EffectiveWeight_i = Weight_i / Σ(AvailableWeights)
Example: Responsiveness is unavailable. Original weights total 100%; available weights total 85%. Skill Match therefore uses 35/85 of the available score mass, Difficulty uses 25/85, and so on.
14.2 New contributor
•	Use onboarding questionnaire and available portfolio/resume evidence to initialize skill estimates.
•	Mark evidence confidence as lower than strong contribution evidence.
•	Use beginner-friendly and low-risk issues when capability evidence is limited.
•	Do not assign Feedback = 0; mark it unavailable until history exists.
•	Use subsequent accepted/completed contributions to improve the profile.
14.3 Contributor without GitHub
GitHub is not mandatory for the conceptual model. Skill evidence can come from other supported contribution platforms, resume/portfolio information, questionnaire responses, and Issue Pilot outcomes. A Chromium contribution history, for example, could be used only if a Chromium evidence adapter is actually implemented; otherwise it should be documented as a future source.
15. Stage 10 — Final Weighted Score
The current initial ranking weights are:
Parameter	Symbol	Weight
Skill Match	S	35%
Difficulty Match	D	25%
Responsiveness	R	15%
Repository Health	H	10%
Feedback / Personalization	F	10%
Freshness	N	5%
Total	—	100%

FinalScore = 0.35S + 0.25D + 0.15R + 0.10H + 0.10F + 0.05N
The weights intentionally make technical suitability and difficulty fit dominant. Repository and maintainer context matter but should not overpower contributor-to-issue suitability. Freshness is a secondary signal.
16. Complete Worked Example
Assume the following final component scores have already been calculated for one contributor and one eligible issue:
Component	Score	Weight	Weighted contribution
Skill Match	81	0.35	28.35
Difficulty Match	76	0.25	19.00
Responsiveness	89	0.15	13.35
Repository Health	83	0.10	8.30
Feedback	75	0.10	7.50
Freshness	89	0.05	4.45

FinalScore = 0.35(81) + 0.25(76) + 0.15(89) + 0.10(83) + 0.10(75) + 0.05(89)
FinalScore = 28.35 + 19.00 + 13.35 + 8.30 + 7.50 + 4.45
FinalScore = 80.95 / 100
Therefore the issue receives a final ranking score of 80.95.
16.1 Traceability of the 81 Skill Match
Technology	Contributor skill	Issue relevance	Contribution
Python	90	0.30	27
FastAPI	80	0.50	40
MongoDB	70	0.20	14

S = 90(0.30) + 80(0.50) + 70(0.20) = 81
16.2 Traceability of the 75 Feedback
Feedback component	Value
Similar accepted/rejected history	12 accepted, 8 rejected
Acceptance score	12/(12+8) × 100 = 60
Accepted/completed history	12 accepted, 9 completed
Completion score	9/12 × 100 = 75
Similarity to successful history	90
Feedback formula	0.30(60) + 0.40(75) + 0.30(90)
Feedback score	75

17. Ranking, Tie-Breaking, and Candidate Selection
After calculating scores for all eligible issues, Issue Pilot sorts issues in descending order.
Issue A = 91.20
Issue B = 88.40
Issue C = 86.75
Issue D = 84.10
...
        ↓
Take top 10–20 candidates
        ↓
Live verification
        ↓
Return final top 5
17.1 Suggested tie-break order
27.	Higher Skill Match.
28.	Higher Difficulty Match.
29.	Higher Freshness.
30.	Lower competition/crowding where reliable data exists.
31.	Stable deterministic native issue identifier as final tie-breaker.
Tie-breaking should not materially alter ranking when scores are meaningfully different. It is primarily for deterministic ordering.
18. Live Verification
Because issue state changes after ingestion, the system should not live-query the entire source corpus for every request. Instead, it ranks the large cached corpus cheaply, then verifies only the top candidates.
Large issue corpus
    ↓
Cheap eligibility filtering
    ↓
Score and rank
    ↓
Top 10–20
    ↓
Live verification
    ├─ still open?
    ├─ still available?
    ├─ still unassigned where supported?
    └─ source data still valid?
    ↓
Remove invalid candidates
    ↓
Backfill from next-ranked candidates
    ↓
Final top 5
This is the 'verify before deliver' principle. A high score is not sufficient if the issue has become unavailable.
19. Failure and Exception Handling
Exception	Required behavior
GitHub unavailable	Use other evidence sources or onboarding evidence; do not assume zero skill.
No contributor history	Cold-start profile; use questionnaire/resume/portfolio and beginner-safe ranking.
No Feedback history	Mark F unavailable and renormalize available weights.
Missing repository responsiveness data	Mark R unavailable and renormalize; do not use R=0.
Issue has no labels	Continue using title/body/repository/other evidence; labels are optional.
Issue difficulty uncertain	Use available difficulty signals and lower confidence; do not invent precise ground truth.
Issue becomes closed after ranking	Discard during live verification.
Issue becomes assigned	Discard where reliable assignment semantics exist.
Source API rate limit	Use cached normalized data and defer live verification/backfill as appropriate.
Duplicate issue	Deduplicate using source + repository + native issue identifier.
Malformed source record	Reject record from ranking and log ingestion error.

19.1 Labels are supporting evidence
Labels such as good-first-issue, backend, documentation, or bug should not receive a major independent weight. They can contribute to technology identification, issue type, or difficulty estimation. This avoids allowing noisy or inconsistent labels to dominate the ranking.
20. Pseudocode
function rank_issues(user, issues):

    profile = build_contributor_profile(user)

    eligible = []
    for issue in issues:
        if not is_eligible(issue):
            continue

        tech_requirements = infer_issue_technologies(issue)
        issue_difficulty = estimate_issue_difficulty(issue)

        S = calculate_skill_match(profile, tech_requirements)

        capability = calculate_contributor_capability(profile)
        D = calculate_difficulty_match(capability, issue_difficulty)

        R = calculate_responsiveness(issue.repository)
        H = calculate_repository_health(issue.repository)

        if has_feedback_history(user):
            F = calculate_feedback(user, issue)
        else:
            F = UNAVAILABLE

        N = calculate_freshness(issue)

        components = {
            "S": S,
            "D": D,
            "R": R,
            "H": H,
            "F": F,
            "N": N
        }

        score = weighted_score_with_missing_data(components)

        eligible.append((issue, score))

    ranked = sort_descending(eligible)

    candidates = ranked[0:20]

    verified = live_verify(candidates)

    while len(verified) < 5 and more_ranked_candidates_exist():
        next_candidates = get_next_ranked_candidates()
        verified.extend(live_verify(next_candidates))

    return top_5(verified)
21. Data Storage and Auditability
For explainability, Issue Pilot should store a recommendation snapshot rather than only the final number. This makes it possible to answer 'Why was this issue recommended?' later.
RecommendationSnapshot
    user_id
    issue_id
    timestamp

    component_scores
        skill_match
        difficulty_match
        responsiveness
        repository_health
        feedback
        freshness

    weights_used
        skill_weight
        difficulty_weight
        responsiveness_weight
        health_weight
        feedback_weight
        freshness_weight

    final_score

    skill_evidence
        technologies
        contributor_skill_scores
        issue_relevance_weights

    difficulty_evidence
    responsiveness_evidence
    health_evidence
    feedback_evidence
    freshness_evidence

    availability_flags
    missing_data_reason
    model_version
A model_version field is important. If weights or formulas change, historical recommendations remain interpretable.
22. Evaluation and Calibration
The ranking model should eventually be evaluated using observed outcomes. The initial 35/25/15/10/10/5 weights should not be presented as empirically optimal.
22.1 Ranking quality metrics
Metric	Meaning
Precision@5	Fraction of the top five recommendations that are relevant.
Recall@5	Fraction of all known relevant issues that appear in the top five.
NDCG@5	Measures relevance while rewarding correct ordering of the best issues near the top.
Acceptance rate	Fraction of delivered recommendations that users accept.
Completion rate	Fraction of accepted recommendations that result in completed contributions.

Precision@5 = Relevant items in top 5 / 5
Recall@5 = Relevant items in top 5 / Total relevant items
NDCG@5 = DCG@5 / IDCG@5
NDCG@5 is especially useful because Issue Pilot is a ranking system: putting the best issue at rank 1 is more valuable than putting it at rank 5.
22.2 Calibration loop
Collect recommendation snapshots
        ↓
Observe accept/reject/complete outcomes
        ↓
Build evaluation dataset
        ↓
Measure Precision@5 / Recall@5 / NDCG@5
        ↓
Tune thresholds and weights
        ↓
Compare against previous model
        ↓
Version the new ranking configuration
23. Security, Fairness, and Abuse Considerations
•	Do not expose private repository information unless the contributor has authorized access and the platform permits it.
•	Do not treat public activity volume as a direct measure of human worth or ability.
•	Avoid allowing a single noisy signal such as labels, stars, or raw commit count to dominate skill estimation.
•	Do not penalize a contributor merely because a platform does not expose a particular field.
•	Use bounded scores so extreme activity cannot grow without limit.
•	Apply recency carefully so old but meaningful experience does not disappear abruptly.
•	Keep model explanations traceable to evidence rather than generating unsupported reasons.
•	Log recommendation decisions for auditability.
24. Implementation Recommendations
24.1 Configuration
Keep all weights and thresholds configurable rather than hard-coded.
ranking:
  skill_weight: 0.35
  difficulty_weight: 0.25
  responsiveness_weight: 0.15
  health_weight: 0.10
  feedback_weight: 0.10
  freshness_weight: 0.05
24.2 Version the model
Store a ranking_model_version with every recommendation snapshot. This makes experiments and later calibration safe.
24.3 Separate eligibility from ranking
Closed or assigned issues should not receive a high ranking and then be rejected afterward if the system can know their state earlier. Eligibility should be a separate stage, with live verification as the final state check.
24.4 Separate raw evidence from scores
Store raw evidence, normalized evidence scores, and final component scores separately. This makes the algorithm debuggable.
24.5 Keep explanations user-readable
Recommended because:
  Skill Match: 81
  Difficulty Match: 76
  Repository Health: 83

Technical match:
  Python 90 × 0.30
  FastAPI 80 × 0.50
  MongoDB 70 × 0.20

Final score:
  80.95 / 100
25. Viva-Ready Explanation
Issue Pilot uses a weighted multi-factor ranking model. For every eligible issue, the system first converts each ranking factor into a normalized score from 0 to 100. The initial model gives 35% importance to Skill Match, 25% to Difficulty Match, 15% to Maintainer Responsiveness, 10% to Repository Health, 10% to Feedback, and 5% to Freshness.
Skill Match is calculated by building a contributor technology profile from evidence such as GitHub activity, other supported contribution sources, and onboarding information. For example, the contributor may have Python = 90, FastAPI = 80, and MongoDB = 70. The issue is separately analyzed to determine technology relevance, such as Python = 0.30, FastAPI = 0.50, and MongoDB = 0.20. The Skill Match is then 90×0.30 + 80×0.50 + 70×0.20 = 81.
Difficulty Match compares contributor capability with repository-relative issue difficulty. Responsiveness measures maintainer interaction quality, Repository Health measures project vitality, Feedback learns from the contributor's previous acceptance and completion behavior, and Freshness measures how recently the issue has been created or active.
The final score is FinalScore = 0.35S + 0.25D + 0.15R + 0.10H + 0.10F + 0.05N. If a component is unavailable, the system does not treat it as zero; it renormalizes the available weights. After ranking the cached issue corpus, Issue Pilot live-verifies the top candidates and returns the final recommendations.
26. Summary of Formulas
Purpose	Formula
Technology skill	Weighted combination of normalized evidence scores
Issue technology relevance	rel(I,t) = raw relevance(t) / Σ raw relevance
Skill Match	S = Σ [skill(u,t) × rel(I,t)]
Difficulty Match	D = 100 − |ContributorCapability − IssueDifficulty|
Acceptance Score	A = Accepted / (Accepted + Rejected) × 100
Completion Score	C = Completed / Accepted × 100
Feedback	F = 0.30A + 0.40C + 0.30Similarity
Freshness	N = 0.40AgeScore + 0.60ActivityScore
Final Score	0.35S + 0.25D + 0.15R + 0.10H + 0.10F + 0.05N
Missing-data score	Σ(AvailableScore_i × Weight_i) / Σ(AvailableWeight_i)

Final Algorithm Summary
1. Ingest and normalize issues.
2. Filter ineligible issues.
3. Build the contributor evidence profile.
4. Calculate contributor technology skill scores.
5. Extract issue technology requirements and relevance weights.
6. Calculate Skill Match S.
7. Estimate contributor capability and issue difficulty; calculate D.
8. Calculate repository/maintainer Responsiveness R.
9. Calculate Repository Health H.
10. Calculate personalized Feedback F when sufficient history exists.
11. Calculate issue Freshness N.
12. Handle unavailable components by weight renormalization.
13. Calculate FinalScore.
14. Sort eligible issues.
15. Live-verify the top 10–20.
16. Backfill invalid candidates.
17. Deliver final top 5 with explanations.
18. Store the score snapshot and model version.
19. Collect outcomes for future calibration.

End of Ranking Algorithm Document
