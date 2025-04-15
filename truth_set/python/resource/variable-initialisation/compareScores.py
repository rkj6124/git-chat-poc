def compare_scores():
    player_score = None
    threshold = 50
    assign_score = True if __import__('random').random() > 0.5 else False
    
    if assign_score:
        player_score = 75
        
    if player_score > threshold:  
        print("Player has a high score!")
    else:
        print("Player score is below the threshold.")

compare_scores()
