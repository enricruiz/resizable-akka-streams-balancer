public class Messages
{
    public static class Email
    {
        public String address;

        public String text;

        public Email(final String address, final String text)
        {
            this.address = address;
            this.text = text;
        }
    }

    public static class SendEmailJob
    {
        public int id;

        public Email email;

        public SendEmailJob(int id, Email email)
        {
            this.id = id;
            this.email = email;
        }
    }

    public static interface DeliveryStatus
    {

    }

    public static class EmailSent implements DeliveryStatus
    {
        public int id;

        public EmailSent(int id)
        {
            this.id = id;
        }
    }

    public static class EmailFailed implements DeliveryStatus
    {
        public int id;

        public EmailFailed(int id)
        {
            this.id = id;
        }
    }

    public static class MailSenderRegistration
    {

    }
}
