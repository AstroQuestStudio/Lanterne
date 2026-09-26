package fr.clubcitrouille.lanterne.chantier;

/**
 * Une requête refusée, avec le code HTTP qui le dit. Levée n'importe où — lecture du corps,
 * validation, ou en plein travail sur le thread serveur — et rendue telle quelle à l'appelant : un
 * refus doit toujours dire pourquoi, jamais se déguiser en erreur 500.
 */
final class Refus extends RuntimeException {
    final int code;

    Refus(int code, String message) {
        super(message, null, false, false);
        this.code = code;
    }
}
