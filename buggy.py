import os

def read_numbers_from_file(filename):
    """Reads numbers from a file and returns them as a list."""
    numbers = []
    with open(filename, "r") as file:
        for line in file:
            numbers.append(int(line.strip()))  # Bug: No error handling for non-integer values
    return numbers

def process_numbers(numbers):
    """Processes numbers: squares even numbers, cubes odd numbers."""
    results = []
    for i in range(len(numbers) + 1):  # Bug: Index out of range
        if numbers[i] % 2 == 0:
            results.append(numbers[i] ** 2)
        else:
            results.append(numbers[i] ** 3)
    return results

def write_numbers_to_file(numbers, output_file):
    """Writes numbers to a file."""
    with open(output_file, "w") as file:
        for num in numbers:
            file.write(str(num) + "\n")  # Bug: Missing file close (though implicit)

def main():
    input_file = "numbers.txt"
    output_file = "output.txt"

    if os.path.exists(input_file):
        numbers = read_numbers_from_file(input_file)
    else:
        print("Error: File not found")  # Bug: Function continues without valid numbers

    processed_numbers = process_numbers(numbers)  # Bug: numbers might be undefined

    write_numbers_to_file(processed_numbers, output_file)  # Bug: Undefined variable if exception occurred

    print("Processing complete. Results written to", output_file)

if __name__ == "__main__":
    main()