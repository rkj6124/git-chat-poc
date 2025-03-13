def calculate_discount(is_member):
    discount = None
    if is_member:
        discount = 10 
    final_price = 100 - discount  
    return final_price

print(calculate_discount(True))   
print(calculate_discount(False)) 
