// write Java code for 8 queens problem
public class EightQueens {
    private static final int SIZE = 8;
    private int[][] board;

    public EightQueens() {
        board = new int[SIZE][SIZE];
    }

    public static void main(String[] args) {
        EightQueens queens = new EightQueens();
        if (queens.solve(0)) {
            queens.printBoard();
        } else {
            System.out.println("No solution found.");
        }
    }

    public boolean solve(int col) {
        if (col >= SIZE) {
            return true;
        }

        for (int i = 0; i < SIZE; i++) {
            if (isSafe(i, col)) {
                board[i][col] = 1;
                if (solve(col + 1)) {
                    return true;
                }
                board[i][col] = 0; // Backtrack
            }
        }

        return false;
    }

    private boolean isSafe(int row, int col) {
        for (int i = 0; i < col; i++) {
            if (board[row][i] == 1) {
                return false;
            }
        }

        for (int i = row, j = col; i >= 0 && j >= 0; i--, j--) {
            if (board[i][j] == 1) {
                return false;
            }
        }

        for (int i = row, j = col; i < SIZE && j >= 0; i++, j--) {
            if (board[i][j] == 1) {
                return false;
            }
        }

        return true;
    }

    public void printBoard() {
        for (int i = 0; i < SIZE; i++) {
            for (int j = 0; j < SIZE; j++) {
                System.out.print(board[i][j] + " ");
            }
            System.out.println();
        }
    }
}
