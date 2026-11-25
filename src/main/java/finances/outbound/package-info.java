/**
 * The only package that may open a network connection, and only to hosts the installer
 * configured (AGENTS.md hard rule 7): today the Enable Banking API host. A guard test keeps the
 * rest of the code from opening connections.
 */
package finances.outbound;
