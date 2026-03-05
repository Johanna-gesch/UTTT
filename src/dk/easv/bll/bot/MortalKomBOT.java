package dk.easv.bll.bot;

import dk.easv.bll.field.IField;
import dk.easv.bll.game.GameState;
import dk.easv.bll.game.IGameState;
import dk.easv.bll.move.IMove;

import java.util.*;

public class MortalKomBOT implements IBot{
    final int moveTimeMs = 990;
    private static final String BOTNAME = "Mortal KomBOT";

    private Map<IMove, Integer> wins = new HashMap<>();
    private Map<IMove, Integer> plays = new HashMap<>();

    @Override
    public IMove doMove(IGameState state){

        return calculateWinningMove(state, moveTimeMs);
    }

    @Override
    public String getBotName(){
        return BOTNAME;
    }

    public enum GameOverState {
        Active,
        Win,
        Tie
    }


    //Move cache er en tabel med 81 genbrugte Move-objekter, én for hver position på brættet
    //Static fordi den tilhører klassen. Final fordi referencen til arrayet ikke kan ændres.
    private static final IMove[][] MOVE_CACHE = new IMove[9][9];
    //Statisk initialiseringsblok. Når MortalKombot bliver indlæst, kører denne blok, og fylder MOVE_CACHE med Move-objekter.
    static {
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 9; y++) {
                MOVE_CACHE[x][y] = new Move(x, y);
            }
        }
    }

    /**
     * Creates a new GameSimulator to simulate the game
     * @param state
     * @return simulator
     */
    private GameSimulator createSimulator(IGameState state) {

        // 1) Lav en kopi af board (9x9)
        String[][] originalBoard = state.getField().getBoard();
        String[][] boardCopy = new String[9][9];
        //For hver række kloner vi, for at få en ny række.
        for (int x = 0; x < 9; x++) {
            boardCopy[x] = originalBoard[x].clone();
        }

        // 2) Lav en kopi af macroboard (3x3)
        String[][] originalMacro = state.getField().getMacroboard();
        String[][] macroCopy = new String[3][3];
        for (int x = 0; x < 3; x++) {
            macroCopy[x] = originalMacro[x].clone();
        }

        // 3) Lav et nyt GameState-objekt, så simulatoren ikke ændrer det rigtige gamestate.
        GameState newState = new GameState();

        newState.setMoveNumber(state.getMoveNumber());
        newState.setRoundNumber(state.getRoundNumber());

        // 4) Sæt de kopierede boards ind i newState
        newState.getField().setBoard(boardCopy);
        newState.getField().setMacroboard(macroCopy);

        // 5) Lav simulatoren
        GameSimulator simulator = new GameSimulator(newState);
        simulator.setGameOver(GameOverState.Active); //simulationen er igang
        simulator.setCurrentPlayer(state.getMoveNumber() % 2);

        return simulator;
    }

    // UCB1: Upper Confidence Bound 1 - en matematisk formel som bruges til at beslutte: "skal jeg vælge noget jeg allerede tror er godt, eller prøve noget nyt
    // UCT: Upper Confidence bounds applied to Trees (Monte Carlo) - basically UCB1 brugt i et spiltræ
    /**
     * Generate a smarter first move using mathmatics
     * @param rootMoves
     * @param wins
     * @param plays
     * @param C
     * @param rand
     * @return bestMove
     */
    private IMove selectRootMoveUCT(List<IMove> rootMoves, Map<IMove, Integer> wins, Map<IMove, Integer> plays, double C, Random rand) {
        // total plays over root
        int totalPlays = 0;
        for (IMove m : rootMoves) totalPlays += plays.getOrDefault(m, 0);

        // Hvis ingen plays endnu -> random
        if (totalPlays == 0) {
            return rootMoves.get(rand.nextInt(rootMoves.size()));
        }

        IMove bestMove = null;
        double bestScore = Double.NEGATIVE_INFINITY; // No matter what, our score will be better than -infinity, and that will be set to out new bestScore

        for (IMove m : rootMoves) {
            int p = plays.getOrDefault(m, 0);

            // Udforsk alle moves mindst 1 gang
            if (p == 0) return m;

            int w = wins.getOrDefault(m, 0);
            double winRate = (double) w / p;

            // UCB1/UCT
            double ucb = winRate + C * Math.sqrt(Math.log(totalPlays) / p);

            // lille tie-break randomness så den ikke låser på samme ved lighed
            if (ucb > bestScore || (ucb == bestScore && rand.nextBoolean())) {
                bestScore = ucb;
                bestMove = m;
            }
        }
        return bestMove;
    }

    // Heuristic move selection for Monte Carlo simulations.
    // Uses simple rules (win, block, good positions) to make future simulated playouts more realistic.
    /**
     * Method to optimize monte carlo playouts.
     * @param sim
     * @param rand
     * @return a different selection of a move from what is possible
     */
    private IMove selectPlayoutMoveHeuristic(GameSimulator sim, Random rand) {
        IGameState s = sim.getCurrentState();
        List<IMove> moves = sim.getAvailableMoves();
        if (moves.isEmpty()) return null;

        int cp = sim.getCurrentPlayer();   // 0/1
        int opp = 1 - cp;

        String cpStr = String.valueOf(cp);
        String oppStr = String.valueOf(opp);

        // 1) Hvis current player kan vinde micro nu -> tag den
        for (IMove m : moves) {
            if (isWinningMove(s, m, cpStr)) return m;
        }

        // 2) Hvis modstander kan vinde micro nu -> blokér
        for (IMove m : moves) {
            if (isWinningMove(s, m, oppStr)) return m;
        }

        // 3) Ellers: "fornuftigt" valg
        return moves.get(rand.nextInt(moves.size()));
    }

    /**
     * Method to play each move
     * @param state
     * @param maxTimeMs
     * @return bestMove
     */
    private IMove calculateWinningMove(IGameState state, int maxTimeMs) {
        long endTime = System.currentTimeMillis() + maxTimeMs;
        Random rand = new Random();

        //PRIORITERINGSLISTE
        // 1) Hvis du kan få macro-win - gør det altid
        IMove macroWin = getImmediateMacroWinMove(state, endTime);
        if (macroWin != null) return macroWin;


        // 2) Safe micro-win - kun hvis det ikke giver opponent micro-/macro-win næste træk
        List<IMove> safeMicroWins = getSafeWinningMoves(state, endTime);
        if (!safeMicroWins.isEmpty()) {
            return safeMicroWins.get(rand.nextInt(safeMicroWins.size()));
        }

        // 3) Bloker modstander
        List<IMove> mustBlock = getOpponentImmediateThreats(state, endTime);
        if (!mustBlock.isEmpty()) {
            return mustBlock.get(0);
        }

        // 4) Hvis ikke du kan 1), 2) eller 3) -> Monte Carlo fallback
        wins.clear();
        plays.clear();

        // Kopi af alle micro-fields i hele spillet
        String[][] originalBoard = state.getField().getBoard();
        String[][] boardCopy = new String[9][9];
        for (int x = 0; x < 9; x++) {
            boardCopy[x] = originalBoard[x].clone();
        }
        // Kopi af alle macro-fields (som er blevet sat) i spillet
        String[][] originalMacro = state.getField().getMacroboard();
        String[][] macroCopy = new String[3][3];
        for (int x = 0; x < 3; x++) {
            macroCopy[x] = originalMacro[x].clone();
        }

        //Hvor er vi henne i spillet?
        int originalMoveNumber = state.getMoveNumber();
        int originalRoundNumber = state.getRoundNumber();
        int originalPlayer = state.getMoveNumber() % 2;

        //Lav simulator med ovenstående informationer
        GameSimulator simulator = createSimulator(state);

        int simCount = 0;
        long startTime = System.currentTimeMillis();

        boolean timeUp = false;
        while (!timeUp) {

            if (System.currentTimeMillis() >= endTime) break;

            simulator.reset(boardCopy, macroCopy, originalMoveNumber, originalRoundNumber, originalPlayer);
            simCount++;
            List<IMove> moves = simulator.getAvailableMoves();
            if (moves.isEmpty()) break;

            Double C = 1.4;
            IMove firstMove = selectRootMoveUCT(moves, wins, plays, C, rand);

            //firstMove bliver tilføjet til vores plays hashmap. V
            plays.merge(firstMove,1, Integer ::sum);

            simulator.updateGame(firstMove);

            // Simulér resten af spillet
            while (simulator.getGameOver() == GameOverState.Active) {

                if (System.currentTimeMillis() >= endTime) {
                    timeUp = true;
                    break;
                }

                // Choose the next move in the simulation using the heuristic policy.
                // This avoids completely random play and produces more realistic playouts.
                IMove playoutMove = selectPlayoutMoveHeuristic(simulator, rand);
                if (playoutMove == null) break;
                simulator.updateGame(playoutMove);
            }

            if (timeUp) break;

            // Simulér resten af spillet (Heavy Playout)


            // Hvis den vandt, husk movet
            int myPlayer = state.getMoveNumber() % 2;
            if (simulator.getGameOver() == GameOverState.Win) {
                int winner = 1 - simulator.currentPlayer; //Kun når det er botten der vandt, og ikke modstanderen
                if (winner == myPlayer) {
                    wins.merge(firstMove,1,Integer ::sum);
                }
            }
        }
        double timeElapsed = (System.currentTimeMillis() - startTime) / 1000.0;
        // Choose the move with the most simulations.
        // Winrate can be misleading with few samples, so the most visited move is usually more reliable.
        IMove bestMove = null;
        int bestPlays = -1;
        //System.out.println("Simulations: " + simCount);
        for (IMove move : plays.keySet()) {
            int p = plays.get(move);
            if (p > bestPlays) {
                bestPlays = p;
                bestMove = move;
            }
        }

        // Hvis ingen statistik → random
        if (bestMove == null) {
            List<IMove> randomMoves = state.getField().getAvailableMoves();
            return randomMoves.get(rand.nextInt(randomMoves.size()));
        }
        // Map bestMove (fra simulator/MOVE_CACHE) til et "rigtigt" move fra state
        List<IMove> legalMoves = state.getField().getAvailableMoves();
        for (IMove m : legalMoves) {
            if (m.getX() == bestMove.getX() && m.getY() == bestMove.getY()) {
                return m; // returnér engine'ens eget IMove-objekt
            }
        }

        // Hvis vi ikke fandt et match (burde ikke ske), så vælg et lovligt move
        Random rando = new Random();
        return legalMoves.get(rando.nextInt(legalMoves.size()));

        //return bestMove;
    }

    /**
     * Checks if we can get a micro-win
     * @param state
     * @param move
     * @param player
     * @return true or false (if it is a winning move or not)
     */
    // Simplified version of checking if there is a win. Check the GameManager class to see another similar solution
    private boolean isWinningMove(IGameState state, IMove move, String player) {
         // Clones the array and all values to a new array, so we don't mess with the game
        String[][] board = Arrays.stream(state.getField().getBoard()).map(String[]::clone).toArray(String[][]::new);

        //Places the player in the game. Sort of a simulation.
        board[move.getX()][move.getY()] = player;

        int startX = move.getX() - (move.getX() % 3);
        if (board[startX][move.getY()].equals(player))
            if (board[startX][move.getY()].equals(board[startX + 1][move.getY()]) &&
                board[startX + 1][move.getY()].equals(board[startX + 2][move.getY()]))
                return true;

        int startY = move.getY() - (move.getY() % 3);
        if (board[move.getX()][startY].equals(player))
            if (board[move.getX()][startY].equals(board[move.getX()][startY + 1]) &&
                board[move.getX()][startY + 1].equals(board[move.getX()][startY + 2]))
                return true;


        if (board[startX][startY].equals(player))
            if (board[startX][startY].equals(board[startX + 1][startY + 1]) &&
                board[startX + 1][startY + 1].equals(board[startX + 2][startY + 2]))
                return true;

        if (board[startX][startY + 2].equals(player))
            return board[startX][startY + 2].equals(board[startX + 1][startY + 1]) &&
                board[startX + 1][startY + 1].equals(board[startX + 2][startY]);

        return false;
    }

    /**
     * Få en liste over alle tilgængelige vinder-træk
     * @param state
     * @return winningmoves
     */
    private List<IMove> getSafeWinningMoves(IGameState state, long endTime){

        String player = "1";
        if (state.getMoveNumber()%2==0)
            player="0";

        int myPlayer = state.getMoveNumber() % 2;
        int oppPlayer = (myPlayer + 1) % 2;

        List<IMove> avail = state.getField().getAvailableMoves();
        List<IMove> winningMoves = new ArrayList<>();

        for (IMove move : avail) {

            if (System.currentTimeMillis() >= endTime) break;

            // Er det et micro-winning move?
            if (!isWinningMove(state, move, player))
                continue;

            // Simulér trækket
            GameSimulator sim = createSimulator(state);
            sim.setCurrentPlayer(myPlayer);
            sim.updateGame(move);

            // Hvis vi selv vandt hele spillet → altid godt
            if (sim.getGameOver() == GameOverState.Win) {
                winningMoves.add(move);
                continue;
            }

            // Tjek om modstander kan vinde direkte bagefter
            IGameState afterMyMove = sim.getCurrentState();
            List<IMove> oppMoves = afterMyMove.getField().getAvailableMoves();

            boolean opponentCanWin = false;

            for (IMove oppMove : oppMoves) {

                if (System.currentTimeMillis() >= endTime) {
                    opponentCanWin = true;
                    break;
                }
                // micro-win check
                if (isWinningMove(afterMyMove, oppMove, "" + oppPlayer)) {
                    opponentCanWin = true;
                    break;
                }

                // macro-win check
                GameSimulator sim2 = createSimulator(afterMyMove);
                sim2.setCurrentPlayer(oppPlayer);
                sim2.updateGame(oppMove);

                if (sim2.getGameOver() == GameOverState.Win) {
                    opponentCanWin = true;
                    break;
                }
            }
            if (!opponentCanWin) {
                winningMoves.add(move);
            }
        }
        return winningMoves;
    }

    /**
     * Vi får en liste over modstanderens vinder-træk
     * @param state af spillet
     * @return liste
     */
    private List<IMove> getOpponentImmediateThreats(IGameState state, long endTime) {

        int opponent = 1 - (state.getMoveNumber() % 2);
        return getWinningMovesForPlayer(state, String.valueOf(opponent), endTime);

    }

    /**
     * Få en liste over vinder-træk for en given spiller.
     * @param state af spillet
     * @param player
     * @return liste.
     */
    private List<IMove> getWinningMovesForPlayer(IGameState state, String player, long endTime) {
        // similar to getWinningMoves but for given player
        List <IMove> avail = state.getField().getAvailableMoves();
        List<IMove> winning = new ArrayList<>();
        for (IMove m : avail) {
            if (System.currentTimeMillis() >= endTime) break;

            if (isWinningMove(state, m, player)) {
                winning.add(m);
            }
        }
        return winning;
    }


    /**
     * Checks for a macro-win
     * @param state
     * @param endTime
     * @return m (macro-win move)
     */
    private IMove getImmediateMacroWinMove(IGameState state, long endTime) {

        int myPlayer = state.getMoveNumber() % 2;
        List<IMove> moves = state.getField().getAvailableMoves();

        for (IMove m : moves) {
            if (System.currentTimeMillis() >= endTime) return null;

            GameSimulator sim = createSimulator(state);
            sim.setCurrentPlayer(myPlayer);
            sim.updateGame(m);
            if (sim.getGameOver() == GameOverState.Win) {
                return m; // vinder hele macro/game
            }
        }
        return null;
    }


    /**
     *
     * NEW CLASS Move
     *
     */
    public static class Move implements IMove {
        int x = 0;
        int y = 0;

        public Move(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public int getX() {
            return x;
        }

        @Override
        public int getY() {
            return y;
        }

        @Override
        public String toString() {
            return "(" + x + "," + y + ")";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Move move = (Move) o;
            return x == move.x && y == move.y;
        }
        @Override
        public int hashCode() {
            return Objects.hash(x, y);
        }
    }

    /**
     *
     * NEW CLASS GameSimulator
     *
     */
    class GameSimulator {
        private final IGameState currentState;
        private int currentPlayer = 0; //player0 == 0 && player1 == 1
        private volatile GameOverState gameOver = GameOverState.Active;

        public void setGameOver(GameOverState state) {
            gameOver = state;
        }

        public GameOverState getGameOver() {
            return gameOver;
        }

        public int getCurrentPlayer() {
            return currentPlayer;
        }

        public void setCurrentPlayer(int player) {
            currentPlayer = player;
        }

        public IGameState getCurrentState() {
            return currentState;
        }

        private final List<IMove> availableMoves = new ArrayList<>();

        public GameSimulator(IGameState currentState) {
            this.currentState = currentState;

            //Fyld availableMoves én gang
            String[][] board = currentState.getField().getBoard();
            String[][] macroBoard = currentState.getField().getMacroboard();

            for (int x=0; x<9; x++) {
                for (int y=0; y<9; y++) {
                    if (board[x][y].equals(IField.EMPTY_FIELD) && macroBoard[x/3][y/3].equals(IField.AVAILABLE_FIELD)) {
                        availableMoves.add(MOVE_CACHE[x][y]);
                    }
                }
            }
        }

        public List<IMove> getAvailableMoves() {
            return availableMoves;
        }

        public void updateGame(IMove move) {
            updateBoard(move);
            currentPlayer = (currentPlayer + 1) % 2;
        }

        private void updateBoard(IMove move) {
            String[][] board = currentState.getField().getBoard();
            board[move.getX()][move.getY()] = currentPlayer + "";
            currentState.setMoveNumber(currentState.getMoveNumber() + 1);
            if (currentState.getMoveNumber() % 2 == 0) {
                currentState.setRoundNumber(currentState.getRoundNumber() + 1);
            }
            checkAndUpdateIfWin(move);
            //Fjerner det move der er spillet
            availableMoves.remove(move);
            //Opdaterer macroboard
            updateMacroboard(move);
            //Genskaber listen
            refreshAvailableMoves();
        }

        public void reset(String[][] boardCopy, String[][] macroCopy, int moveNumber, int roundNumber, int currentPlayer) {
            String[][] board = currentState.getField().getBoard();
            for (int x=0; x<9; x++) {
                board[x] = boardCopy[x].clone();
            }

            String[][] macro = currentState.getField().getMacroboard();
            for (int x=0; x<3; x++) {
                macro[x] = macroCopy[x].clone();
            }

            currentState.setMoveNumber(moveNumber);
            currentState.setRoundNumber(roundNumber);
            this.currentPlayer = currentPlayer;
            refreshAvailableMoves();
            gameOver = GameOverState.Active;
        }


        //Scanner brættene og tilføjer lovlige træk
        private void refreshAvailableMoves() {
            availableMoves.clear();

            String[][] board = currentState.getField().getBoard();
            String[][] macroBoard = currentState.getField().getMacroboard();

            for (int x=0; x<9; x++) {
                for (int y=0; y<9; y++) {
                    if (board[x][y].equals(IField.EMPTY_FIELD)
                            && macroBoard[x/3][y/3].equals(IField.AVAILABLE_FIELD)) {
                        availableMoves.add(MOVE_CACHE[x][y]);
                    }
                }
            }
        }

        private void checkAndUpdateIfWin(IMove move) {
            String[][] macroBoard = currentState.getField().getMacroboard();
            int macroX = move.getX() / 3;
            int macroY = move.getY() / 3;

            if (macroBoard[macroX][macroY].equals(IField.EMPTY_FIELD) ||
                    macroBoard[macroX][macroY].equals(IField.AVAILABLE_FIELD)) {

                String[][] board = getCurrentState().getField().getBoard();

                if (isWin(board, move, "" + currentPlayer))
                    macroBoard[macroX][macroY] = currentPlayer + "";
                else if (isTie(board, move))
                    macroBoard[macroX][macroY] = "TIE";

                //Check macro win
                if (isWin(macroBoard, new Move(macroX, macroY), "" + currentPlayer))
                    gameOver = GameOverState.Win;
                else if (isTie(macroBoard, new Move(macroX, macroY)))
                    gameOver = GameOverState.Tie;
            }
        }

        private boolean isTie(String[][] board, IMove move) {
            int localX = move.getX() % 3;
            int localY = move.getY() % 3;
            int startX = move.getX() - (localX);
            int startY = move.getY() - (localY);

            for (int i = startX; i < startX + 3; i++) {
                for (int k = startY; k < startY + 3; k++) {
                    if (board[i][k].equals(IField.AVAILABLE_FIELD) ||
                            board[i][k].equals(IField.EMPTY_FIELD))
                        return false;
                }
            }
            return true;
        }


        public boolean isWin(String[][] board, IMove move, String currentPlayer) {
            int localX = move.getX() % 3;
            int localY = move.getY() % 3;
            int startX = move.getX() - (localX);
            int startY = move.getY() - (localY);

            //check col
            for (int i = startY; i < startY + 3; i++) {
                if (!board[move.getX()][i].equals(currentPlayer))
                    break;
                if (i == startY + 3 - 1) return true;
            }

            //check row
            for (int i = startX; i < startX + 3; i++) {
                if (!board[i][move.getY()].equals(currentPlayer))
                    break;
                if (i == startX + 3 - 1) return true;
            }

            //check diagonal
            if (localX == localY) {
                //we're on a diagonal
                int y = startY;
                for (int i = startX; i < startX + 3; i++) {
                    if (!board[i][y++].equals(currentPlayer))
                        break;
                    if (i == startX + 3 - 1) return true;
                }
            }

            //check anti diagonal
            if (localX + localY == 3 - 1) {
                int less = 0;
                for (int i = startX; i < startX + 3; i++) {
                    if (!board[i][(startY + 2) - less++].equals(currentPlayer))
                        break;
                    if (i == startX + 3 - 1) return true;
                }
            }
            return false;
        }

        private void updateMacroboard(IMove move) {
            String[][] macroBoard = currentState.getField().getMacroboard();
            for (int i = 0; i < macroBoard.length; i++) {
                for (int k = 0; k < macroBoard[i].length; k++) {
                    if (macroBoard[i][k].equals(IField.AVAILABLE_FIELD))
                        macroBoard[i][k] = IField.EMPTY_FIELD;
                }
            }

            int xTrans = move.getX() % 3;
            int yTrans = move.getY() % 3;

            if (macroBoard[xTrans][yTrans].equals(IField.EMPTY_FIELD))
                macroBoard[xTrans][yTrans] = IField.AVAILABLE_FIELD;
            else {
                // Field is already won, set all fields not won to avail.
                for (int i = 0; i < macroBoard.length; i++) {
                    for (int k = 0; k < macroBoard[i].length; k++) {
                        if (macroBoard[i][k].equals(IField.EMPTY_FIELD))
                            macroBoard[i][k] = IField.AVAILABLE_FIELD;
                    }
                }
            }
        }
    }
}

