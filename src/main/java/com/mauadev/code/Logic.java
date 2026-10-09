```java
package com.mauadev.code;

import com.mauadev.code.entities.Coordinate;
import com.mauadev.code.entities.GameState;
import com.mauadev.code.entities.Board;
import com.mauadev.code.entities.Snake;

import java.util.*;

public class Logic {

    private static final String[] DIRECTIONS = {
        "up", "down", "left", "right"
    };

    private static final int UP = 0;
    private static final int DOWN = 1;
    private static final int LEFT = 2;
    private static final int RIGHT = 3;

    private static final double DEAD = -1_000_000_000.0;

    public static Map<String, String> info() {
        Map<String, String> info = new HashMap<>();
        info.put("apiversion", "1");
        info.put("author", "");
        info.put("color", "#000000ff");
        info.put("head", "evil");
        info.put("tail", "bolt");
        return info;
    }

    public static void start(GameState state) {
    }

    public static void end(GameState state) {
    }

    public static String getMove(GameState state) {
        try {
            if (state == null
                    || state.getBoard() == null
                    || state.getYou() == null
                    || state.getYou().getHead() == null) {
                return "up";
            }

            Engine engine = new Engine(state);
            return engine.chooseMove();

        } catch (Exception e) {
            // A resposta nunca deve deixar de ser uma direção válida.
            return "up";
        }
    }

    private static final class Pos {
        final int x;
        final int y;

        Pos(int x, int y) {
            this.x = x;
            this.y = y;
        }

        Pos move(int direction) {
            switch (direction) {
                case UP:
                    return new Pos(x, y + 1);
                case DOWN:
                    return new Pos(x, y - 1);
                case LEFT:
                    return new Pos(x - 1, y);
                case RIGHT:
                    return new Pos(x + 1, y);
                default:
                    return this;
            }
        }

        int distance(Pos other) {
            return Math.abs(x - other.x)
                    + Math.abs(y - other.y);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Pos)) return false;

            Pos other = (Pos) obj;
            return x == other.x && y == other.y;
        }

        @Override
        public int hashCode() {
            return 31 * x + y;
        }
    }

    private static final class SnakeData {
        final String id;
        final int health;
        final List<Pos> body;

        SnakeData(String id, int health, List<Pos> body) {
            this.id = id;
            this.health = health;
            this.body = body;
        }

        Pos head() {
            return body.isEmpty() ? null : body.get(0);
        }

        Pos tail() {
            return body.isEmpty()
                    ? null
                    : body.get(body.size() - 1);
        }

        int length() {
            return body.size();
        }
    }

    private static final class Engine {

        final int width;
        final int height;
        final int turn;
        final int cells;

        final SnakeData me;
        final List<SnakeData> enemies = new ArrayList<>();
        final List<Pos> food = new ArrayList<>();
        final Set<Pos> hazards = new HashSet<>();

        final long deadline;

        Engine(GameState state) {
            Board board = state.getBoard();
            Snake you = state.getYou();

            width = board.getWidth();
            height = board.getHeight();
            turn = state.getTurn();

            if (width <= 0 || height <= 0
                    || (long) width * height > 2500) {
                throw new IllegalArgumentException("Tabuleiro inválido");
            }

            cells = width * height;

            me = parseSnake(you);

            for (Snake snake : safeList(board.getSnakes())) {
                if (snake == null
                        || Objects.equals(snake.getId(), you.getId())) {
                    continue;
                }

                SnakeData enemy = parseSnake(snake);

                // Ignora entradas inválidas sem cabeça.
                if (enemy.head() != null) {
                    enemies.add(enemy);
                }
            }

            for (Coordinate c : safeList(board.getFood())) {
                Pos p = parsePos(c);
                if (inside(p)) {
                    food.add(p);
                }
            }

            for (Coordinate c : safeList(board.getHazards())) {
                Pos p = parsePos(c);
                if (inside(p)) {
                    hazards.add(p);
                }
            }

            int timeout = state.getGame() == null
                    ? 500
                    : state.getGame().getTimeout();

            if (timeout <= 0) {
                timeout = 500;
            }

            // Reserva margem para a Lambda e para a rede.
            long budgetMs = Math.max(5,
                    Math.min(65, timeout - 180));

            deadline = System.nanoTime()
                    + budgetMs * 1_000_000L;
        }

        private <T> List<T> safeList(List<T> list) {
            return list == null
                    ? Collections.emptyList()
                    : list;
        }

        private SnakeData parseSnake(Snake snake) {
            List<Pos> body = new ArrayList<>();

            for (Coordinate c : safeList(snake.getBody())) {
                Pos p = parsePos(c);
                if (p != null) {
                    body.add(p);
                }
            }

            if (body.isEmpty() && snake.getHead() != null) {
                body.add(parsePos(snake.getHead()));
            }

            return new SnakeData(
                    snake.getId(),
                    snake.getHealth(),
                    body
            );
        }

        private Pos parsePos(Coordinate c) {
            if (c == null) return null;
            return new Pos(c.getX(), c.getY());
        }

        boolean inside(Pos p) {
            return p != null
                    && p.x >= 0 && p.x < width
                    && p.y >= 0 && p.y < height;
        }

        boolean isFood(Pos p) {
            return food.contains(p);
        }

        boolean isHazard(Pos p) {
            return hazards.contains(p);
        }

        boolean nearWall(Pos p) {
            return p.x == 0 || p.x == width - 1
                    || p.y == 0 || p.y == height - 1;
        }

        /*
         * As caudas podem liberar a casa na próxima jogada.
         * Mantemos bloqueados todos os outros segmentos.
         */
        Set<Pos> occupied(boolean growing) {
            Set<Pos> blocked = new HashSet<>();

            addBody(blocked, me, growing);

            for (SnakeData enemy : enemies) {
                addBody(blocked, enemy, false);
            }

            return blocked;
        }

        private void addBody(
                Set<Pos> blocked,
                SnakeData snake,
                boolean growing
        ) {
            int limit = snake.body.size();

            // A cauda só sai se não houver crescimento.
            if (!growing && limit > 0) {
                limit--;
            }

            for (int i = 0; i < limit; i++) {
                Pos p = snake.body.get(i);
                if (inside(p)) {
                    blocked.add(p);
                }
            }
        }

        /*
         * Verifica a regra de não voltar pelo pescoço.
         */
        boolean reversesIntoNeck(Pos next) {
            return me.body.size() >= 2
                    && next.equals(me.body.get(1));
        }

        /*
         * Regra especial da arena:
         * nos primeiros turnos, se a cabeça estiver na segunda
         * linha de qualquer extremidade, não subir.
         */
        boolean forbiddenSpawnMove(int direction) {
            if (direction != UP || turn > 3) {
                return false;
            }

            Pos head = me.head();

            return head.y == 1 || head.y == height - 2;
        }

        /*
         * Casas em que uma cobra adversária pode chegar à nossa
         * cabeça no próximo turno. Empates de tamanho também são
         * perigosos.
         */
        boolean dangerousHeadCollision(Pos next, boolean growing) {
            int myLength = me.length() + (growing ? 1 : 0);

            for (SnakeData enemy : enemies) {
                Pos head = enemy.head();

                if (head == null || enemy.length() < myLength) {
                    continue;
                }

                for (int d = 0; d < 4; d++) {
                    Pos enemyNext = head.move(d);

                    if (!inside(enemyNext)) {
                        continue;
                    }

                    if (enemyNext.equals(next)) {
                        return true;
                    }
                }
            }

            return false;
        }

        /*
         * Uma casa é candidata se não sai do tabuleiro, não
         * volta pelo pescoço e não colide com um corpo bloqueado.
         */
        List<Integer> legalMoves() {
            List<Integer> result = new ArrayList<>();
            Pos head = me.head();

            if (head == null) return result;

            for (int d = 0; d < 4; d++) {
                Pos next = head.move(d);

                if (!inside(next)) continue;
                if (reversesIntoNeck(next)) continue;
                if (forbiddenSpawnMove(d)) continue;

                boolean growing = isFood(next);
                Set<Pos> blocked = occupied(growing);

                if (blocked.contains(next)) continue;

                // Não podemos sobreviver sem comida quando a
                // saúde chega a zero no próximo movimento.
                if (me.health <= 1 && !growing) continue;

                result.add(d);
            }

            return result;
        }

        /*
         * BFS: calcula distâncias reais, sem atravessar paredes,
         * corpos, hazards ou casas proibidas.
         */
        int[] distances(Pos start, Set<Pos> blocked) {
            int[] dist = new int[cells];
            Arrays.fill(dist, Integer.MAX_VALUE);

            if (!inside(start)) return dist;

            int[] queue = new int[cells];
            int read = 0;
            int write = 0;

            int origin = index(start);
            queue[write++] = origin;
            dist[origin] = 0;

            while (read < write) {
                checkTime();

                int current = queue[read++];
                Pos p = fromIndex(current);

                for (int d = 0; d < 4; d++) {
                    Pos next = p.move(d);

                    if (!inside(next)) continue;
                    if (blocked.contains(next)) continue;
                    if (isHazard(next)) continue;

                    int idx = index(next);

                    if (dist[idx] != Integer.MAX_VALUE) {
                        continue;
                    }

                    dist[idx] = dist[current] + 1;
                    queue[write++] = idx;
                }
            }

            return dist;
        }

        int index(Pos p) {
            return p.y * width + p.x;
        }

        Pos fromIndex(int i) {
            return new Pos(i % width, i / width);
        }

        int reachableSpace(Pos start, Set<Pos> blocked) {
            return countReachable(distances(start, blocked));
        }

        int countReachable(int[] distances) {
            int count = 0;

            for (int d : distances) {
                if (d != Integer.MAX_VALUE) {
                    count++;
                }
            }

            return count;
        }

        /*
         * Busca a comida mais acessível por caminho livre,
         * em vez de usar apenas distância em linha reta.
         */
        int nearestFoodDistance(int[] distances) {
            int best = Integer.MAX_VALUE;

            for (Pos p : food) {
                if (!inside(p)) continue;

                int d = distances[index(p)];
                best = Math.min(best, d);
            }

            return best;
        }

        /*
         * Estima a distância que o adversário percorre até a
         * comida. Penaliza disputas que provavelmente perderemos.
         */
        int nearestEnemyDistance(Pos target) {
            int best = Integer.MAX_VALUE;

            for (SnakeData enemy : enemies) {
                Pos head = enemy.head();
                if (head == null) continue;

                int distance = head.distance(target);
                best = Math.min(best, distance);
            }

            return best;
        }

        double scoreMove(int direction) {
            Pos next = me.head().move(direction);
            boolean growing = isFood(next);

            Set<Pos> blocked = occupied(growing);

            if (!inside(next) || blocked.contains(next)) {
                return DEAD;
            }

            double score = 0;

            int nextHealth = growing ? 100 : me.health - 1;
            int nextLength = me.length() + (growing ? 1 : 0);

            // 1. Sobrevivência e espaço.
            int space = reachableSpace(next, blocked);

            if (space < nextLength) {
                score -= 500_000
                        + (nextLength - space) * 20_000.0;
            } else {
                score += Math.min(space, nextLength * 3 + 15) * 100.0;
            }

            // 2. Previsão de colisões de cabeça.
            if (dangerousHeadCollision(next, growing)) {
                score -= 100_000;
            }

            // 3. Evita regiões apertadas e becos sem saída.
            int exits = 0;

            for (int d = 0; d < 4; d++) {
                Pos neighbor = next.move(d);

                if (inside(neighbor)
                        && !blocked.contains(neighbor)
                        && !neighbor.equals(me.head())) {
                    exits++;
                }
            }

            score += exits * 80.0;

            // 4. Busca comida usando caminho real.
            if (!food.isEmpty()) {
                int[] dist = distances(next, blocked);
                int foodDistance = nearestFoodDistance(dist);

                if (foodDistance == Integer.MAX_VALUE) {
                    score -= 2_000;
                } else {
                    double hungerWeight;

                    if (nextHealth <= 20) {
                        hungerWeight = 2_000;
                    } else if (nextHealth <= 40) {
                        hungerWeight = 800;
                    } else if (nextHealth <= 65) {
                        hungerWeight = 250;
                    } else {
                        hungerWeight = 70;
                    }

                    score -= foodDistance * hungerWeight;

                    if (growing) {
                        score += nextHealth < 65
                                ? 15_000
                                : 3_000;

                        int enemyDistance = nearestEnemyDistance(next);

                        if (enemyDistance <= 1
                                && enemies.stream().anyMatch(
                                    e -> e.length() >= nextLength)) {
                            score -= 20_000;
                        }
                    }
                }
            } else if (nextHealth < 35) {
                score -= 15_000;
            }

            // 5. Penaliza hazards, especialmente com pouca vida.
            if (isHazard(next)) {
                score -= nextHealth < 30 ? 20_000 : 2_500;
            }

            // 6. Evita ficar colado às paredes.
            if (nearWall(next)) {
                score -= 250;
            }

            // 7. Favorece o centro sem sacrificar segurança.
            double centerX = (width - 1) / 2.0;
            double centerY = (height - 1) / 2.0;

            double centerDistance =
                    Math.abs(next.x - centerX)
                    + Math.abs(next.y - centerY);

            score -= centerDistance * 3.0;

            // 8. Se estivermos grandes e com vida, evitamos
            // movimentos que nos prendam junto de uma cobra maior.
            for (SnakeData enemy : enemies) {
                Pos enemyHead = enemy.head();

                if (enemyHead == null) continue;

                int distance = next.distance(enemyHead);

                if (enemy.length() >= nextLength && distance <= 2) {
                    score -= (3 - distance) * 800.0;
                }
            }

            // 9. Penaliza escolhas que nos deixam sem saída
            // mesmo que a área pareça grande à primeira vista.
            if (exits <= 1 && space < nextLength * 2) {
                score -= 10_000;
            }

            // 10. Com saúde alta e vantagem de tamanho,
            // prefere espaço em vez de perseguição suicida.
            int maxEnemyLength = 0;

            for (SnakeData enemy : enemies) {
                maxEnemyLength = Math.max(
                        maxEnemyLength,
                        enemy.length()
                );
            }

            if (nextLength > maxEnemyLength && nextHealth > 50) {
                score += Math.min(space, 100) * 20.0;
            }

            return score;
        }

        void checkTime() {
            if (System.nanoTime() >= deadline) {
                throw new RuntimeException("Tempo de busca excedido");
            }
        }

        String chooseMove() {
            List<Integer> legal = legalMoves();

            if (legal.isEmpty()) {
                return emergencyMove();
            }

            int bestDirection = legal.get(0);
            double bestScore = -Double.MAX_VALUE;

            for (int direction : legal) {
                double score;

                try {
                    score = scoreMove(direction);
                } catch (RuntimeException e) {
                    // Se a busca passar do orçamento, conserva
                    // a melhor decisão já calculada.
                    break;
                }

                if (score > bestScore) {
                    bestScore = score;
                    bestDirection = direction;
                }
            }

            return DIRECTIONS[bestDirection];
        }

        /*
         * Último recurso: escolhe a direção com menor risco
         * geométrico quando todas as opções parecem perigosas.
         */
        String emergencyMove() {
            Pos head = me.head();

            if (head == null) return "up";

            int bestDirection = UP;
            double bestScore = -Double.MAX_VALUE;

            for (int d = 0; d < 4; d++) {
                Pos next = head.move(d);
                double score = 0;

                if (!inside(next)) {
                    score -= 1_000_000;
                } else {
                    score += 100;

                    if (reversesIntoNeck(next)) {
                        score -= 100_000;
                    }

                    if (occupied(isFood(next)).contains(next)) {
                        score -= 50_000;
                    }

                    if (forbiddenSpawnMove(d)) {
                        score -= 100_000;
                    }

                    if (dangerousHeadCollision(next, isFood(next))) {
                        score -= 25_000;
                    }

                    if (isFood(next)) {
                        score += 5_000;
                    }
                }

                if (score > bestScore) {
                    bestScore = score;
                    bestDirection = d;
                }
            }

            return DIRECTIONS[bestDirection];
        }
    }
}
```