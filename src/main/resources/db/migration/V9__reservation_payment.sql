-- Closing a reservation against the payment that settled it (issue #51): a nullable link from `reservation` to `transactions`.
-- It is null while the reservation is open, and for one closed without a payment; reopening clears it. Nothing is adjusted by it:
-- the page only shows the difference between what was paid and what was reserved. Several reservations may point at one transaction
-- (a combined payment), so there is no unique index.
--
-- Only a booked transaction is ever linked (ReservationService checks). The one delete of a transaction in the application is
-- TransactionService.deliverFromBank, and it removes pending bank rows only, so it can never reach a linked row; no `on delete`
-- action is declared, and a delete of a linked row would be refused by the foreign key rather than silently break the link.
alter table reservation add column transaction_id integer references transactions (id);
