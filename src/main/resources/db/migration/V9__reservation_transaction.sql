-- Closing a reservation against the payment it was for (issue #51): a closed reservation may keep a link to one booked
-- transaction. Nothing is adjusted; the link only lets the page show what was paid next to what was reserved.
-- Only booked rows are linked: a bank sync deletes pending rows that have no note, a booked row is never deleted.
-- Reopening a reservation clears the link. (V8 expected a separate table; one closed reservation has one payment at
-- most, so a nullable column is enough. V8 is not edited.)
alter table reservation add column transaction_id integer references transactions (id);

create index reservation_by_transaction on reservation (transaction_id);
