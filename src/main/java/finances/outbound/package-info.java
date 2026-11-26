/**
 * The only package that may open a network connection, and only to hosts the installer
 * configured (AGENTS.md hard rule 7): the Enable Banking API host and the after-tax source (the one URL
 * under {@code finances.outbound.after-tax.url}). A guard test keeps the rest of the code from opening connections.
 */
package finances.outbound;
