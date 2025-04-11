function compareScores() {
    let playerScore: number;
    let threshold = 50;

    const assignScore = Math.random() > 0.5;
    if (assignScore) {
        playerScore = 75;
    }

    if (playerScore > threshold) {
        console.log("Player has a high score!");
    } else {
        console.log("Player score is below the threshold.");
    }
}

compareScores();